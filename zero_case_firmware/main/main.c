#include <string.h>
#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"111
#include "esp_flash.h"
#include "esp_heap_caps.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_gatt.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "led_strip.h"
#include "jpeg_decoder.h"

static const char *TAG = "ZERO_BOX";

// ==================== BLE ====================
#define BLE_DEVICE_NAME    "zero box"
#define ZEROBOX_SVC_UUID   0xFF01
#define ZEROBOX_CMD_UUID   0xFF02
#define ZEROBOX_DATA_UUID  0xFF03
#define ZEROBOX_ACK_UUID   0xFF04

// ==================== 硬件 ====================
#define WS2812_GPIO_NUM    38
#define LED_NUM            7
#define LED_MODE_ON        1
#define LED_MODE_BREATH    2
#define LED_MODE_PULSE     3
#define LED_MODE_AUDIO     4

#define IMAGE_W           320
#define IMAGE_H           172
#define IMAGE_SIZE        (IMAGE_W * IMAGE_H * 2)

#define LCD_HOST          SPI2_HOST
#define LCD_SCL           17
#define LCD_SDA           16
#define LCD_CS            18
#define LCD_DC            15
#define LCD_RES           7


// ==================== Flash 存储 ====================
#define MEDIA_FLASH_ADDR    0x320000
#define MEDIA_FLASH_SIZE    (14 * 1024 * 1024)
#define MEDIA_SECTOR_SIZE   8192
#define MEDIA_DATA_START    (MEDIA_FLASH_ADDR + MEDIA_SECTOR_SIZE)

#define IMAGE_MAGIC         0x494D4700
#define VIDEO_MAGIC         0x56494445
#define MAX_VIDEO_FRAMES    1422
#include "freertos/queue.h"
static const uint8_t audio_lut[] = {
     3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
    19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
    35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
    51, 53, 55, 58, 61, 65, 69, 74, 79, 85, 91, 98,105,113,121,130,
   139,148,158,168,178,189,200,211,222,233,244,255
};

// ==================== JPEG 缓冲 ====================
#define JPEG_BUF_SIZE      (96 * 1024)
typedef struct {
    uint8_t *data;
    uint32_t len;
} flash_write_item_t;
static QueueHandle_t flash_queue = NULL;

static volatile int64_t last_display_us = 0;
static int disp_direct_count = 0;
static int disp_blend_count = 0;
static int64_t disp_log_t0 = 0;

static uint8_t  jpeg_buf[JPEG_BUF_SIZE] __attribute__((aligned(4)));
static int      jpeg_offset = 0;
static int      jpeg_expected = 0;
static bool     jpeg_receiving = false;

static uint8_t  g_image_data[IMAGE_SIZE] __attribute__((aligned(4)));
static bool     g_image_valid = false;

static uint16_t audio_bands[7] = {0};
static uint8_t  audio_level = 0;
static bool     audio_active = false;
static uint8_t *vbuf[4] = {NULL, NULL, NULL, NULL};

// ==================== LED ====================
static uint8_t led_current_mode = LED_MODE_ON;
static uint8_t prev_mode = 0;
static uint8_t target_r = 255, target_g = 255, target_b = 255;
static uint8_t global_brightness = 255;

static led_strip_handle_t  led_strip;
static spi_device_handle_t  lcd_spi;

// ==================== BLE 状态 ====================
static uint16_t ble_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint8_t  ble_addr_type;
static int      ble_img_offset = 0;
static bool     ble_img_receiving = false;
static SemaphoreHandle_t img_save_sem = NULL;
static SemaphoreHandle_t jpeg_decode_sem = NULL;

static uint16_t ack_chr_val_handle = 0;
static bool     ack_subscribed = false;

// ==================== 视频接收状态 ====================
static bool     video_receiving = false;
static int      video_state = 0;
static int      video_total_frames = 0;
static int      video_current_frame = 0;
static int      video_frame_size = 0;
static int      video_frame_offset = 0;
static uint32_t video_frame_sizes[MAX_VIDEO_FRAMES];
static int      video_queued_frames = 0;
static uint32_t video_flash_addr = 0;
static uint32_t video_next_erase = 0;

// ==================== 视频播放状态（从 Flash 加载）====================
static volatile bool     has_stored_video = false;
static int      stored_video_frames = 0;
static uint32_t stored_frame_sizes[MAX_VIDEO_FRAMES];
static uint32_t stored_frame_offsets[MAX_VIDEO_FRAMES];

// 播放控制
static volatile bool     playback_active = false;
static volatile int video_progress_percent = -1;
static bool screen_cleared = false;

// 双核播放
static QueueHandle_t display_queue = NULL;
static QueueHandle_t buf_free_queue = NULL;

#define NVS_CFG_NAMESPACE  "led_cfg"

// ==================== 亮度计算 ====================
static inline uint8_t calc_brightness(uint8_t phase) {
    uint32_t x = (phase < 128) ? phase : (256 - phase);
    return (uint8_t)(5 + (250 * x * (256 - x)) / 16384);
}

// ==================== JPEG 解码 ====================
static esp_err_t decode_jpeg_to_rgb565(const uint8_t *jpg, int jpg_len,
                                        uint8_t *out, int out_size) {
    esp_jpeg_image_cfg_t cfg = {
        .indata = (uint8_t *)jpg,
        .indata_size = jpg_len,
        .outbuf = out,
        .outbuf_size = out_size,
        .out_format = JPEG_IMAGE_FORMAT_RGB565,
        .out_scale = JPEG_IMAGE_SCALE_0,
        .flags = { .swap_color_bytes = 1 },
    };
    esp_jpeg_image_output_t img;
    return esp_jpeg_decode(&cfg, &img);
}

// ==================== BLE ACK 发送 ====================
static void send_ack(uint8_t ack_code) {
    if (!ack_subscribed || ble_conn_handle == BLE_HS_CONN_HANDLE_NONE) return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&ack_code, 1);
    if (om) {
        ble_gattc_notify_custom(ble_conn_handle, ack_chr_val_handle, om);
    }
}

static void save_image_to_flash(const uint8_t *data, size_t len) {
    if (len != IMAGE_SIZE) return;
    playback_active = false;
    has_stored_video = false;
    size_t erase_size = MEDIA_SECTOR_SIZE + len;
    erase_size = (erase_size + MEDIA_SECTOR_SIZE - 1) & ~(MEDIA_SECTOR_SIZE - 1);
    esp_flash_erase_region(NULL, MEDIA_FLASH_ADDR, erase_size);
    uint32_t header[2] = { IMAGE_MAGIC, IMAGE_SIZE };
    esp_flash_write(NULL, header, MEDIA_FLASH_ADDR, sizeof(header));
    esp_flash_write(NULL, data, MEDIA_DATA_START, len);
    memcpy(g_image_data, data, len);
    g_image_valid = true;
    ESP_LOGI(TAG, "Image saved to flash");
}

static void load_image_from_flash(void) {
    uint32_t header[2];
    esp_flash_read(NULL, header, MEDIA_FLASH_ADDR, sizeof(header));
    if (header[0] != IMAGE_MAGIC) return;
    if (header[1] != IMAGE_SIZE) return;
    esp_flash_read(NULL, g_image_data, MEDIA_DATA_START, IMAGE_SIZE);
    g_image_valid = true;
    ESP_LOGI(TAG, "Image loaded from flash");
}

// ==================== Flash: 视频存取 ====================
static void finalize_video_save(int frame_count) {
	video_progress_percent = -1;
	screen_cleared = false;
    uint32_t header[4];
    header[0] = VIDEO_MAGIC;
    header[1] = (uint32_t)frame_count;
    header[2] = 0;
    header[3] = 0;

    esp_flash_write(NULL, header, MEDIA_FLASH_ADDR, sizeof(header));
    esp_flash_write(NULL, video_frame_sizes,
                     MEDIA_FLASH_ADDR + sizeof(header),
                     frame_count * 4);

    has_stored_video = true;
    stored_video_frames = frame_count;
    memcpy(stored_frame_sizes, video_frame_sizes, frame_count * 4);

    stored_frame_offsets[0] = 0;
    for (int i = 1; i < frame_count; i++) {
        stored_frame_offsets[i] = stored_frame_offsets[i - 1] + stored_frame_sizes[i - 1];
    }
    ESP_LOGI(TAG, "Video saved: %d frames", frame_count);
}

static void load_video_from_flash(void) {
    uint32_t header[4];
    esp_flash_read(NULL, header, MEDIA_FLASH_ADDR, sizeof(header));
    if (header[0] != VIDEO_MAGIC) return;
    stored_video_frames = (int)header[1];
    if (stored_video_frames <= 0 || stored_video_frames > MAX_VIDEO_FRAMES) return;

    esp_flash_read(NULL, stored_frame_sizes,
                     MEDIA_FLASH_ADDR + sizeof(header),
                     stored_video_frames * 4);

    stored_frame_offsets[0] = 0;
    for (int i = 1; i < stored_video_frames; i++) {
        stored_frame_offsets[i] = stored_frame_offsets[i - 1] + stored_frame_sizes[i - 1];
    }
    has_stored_video = true;
    ESP_LOGI(TAG, "Video loaded: %d frames", stored_video_frames);
}

static void load_media_from_flash(void) {
    uint32_t magic;
    esp_flash_read(NULL, &magic, MEDIA_FLASH_ADDR, 4);
    if (magic == VIDEO_MAGIC) load_video_from_flash();
    else if (magic == IMAGE_MAGIC) load_image_from_flash();
    else ESP_LOGW(TAG, "No saved media");
}

// ==================== LCD ====================
static void lcd_cmd(uint8_t cmd) {
    gpio_set_level(LCD_DC, 0);
    spi_transaction_t t = {.length = 8, .tx_buffer = &cmd};
    spi_device_transmit(lcd_spi, &t);
}

static void lcd_data(uint8_t data) {
    gpio_set_level(LCD_DC, 1);
    spi_transaction_t t = {.length = 8, .tx_buffer = &data};
    spi_device_transmit(lcd_spi, &t);
}

static void lcd_set_window(uint16_t x1, uint16_t y1, uint16_t x2, uint16_t y2) {
    y1 += 34; y2 += 34;
    lcd_cmd(0x2A);
    lcd_data(x1 >> 8); lcd_data(x1);
    lcd_data(x2 >> 8); lcd_data(x2);
    lcd_cmd(0x2B);
    lcd_data(y1 >> 8); lcd_data(y1);
    lcd_data(y2 >> 8); lcd_data(y2);
    lcd_cmd(0x2C);
}

void lcd_init(void) {
    gpio_set_direction(LCD_RES, GPIO_MODE_OUTPUT);
    gpio_set_direction(LCD_DC, GPIO_MODE_OUTPUT);
    gpio_set_level(LCD_RES, 0); vTaskDelay(20);
    gpio_set_level(LCD_RES, 1); vTaskDelay(120);

        spi_bus_config_t bus_cfg = {
        .mosi_io_num = LCD_SDA,
        .sclk_io_num = LCD_SCL,
        .miso_io_num = -1,
        
    };
    spi_bus_initialize(LCD_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = 80 * 1000 * 1000,
        .mode = 0,
        .spics_io_num = LCD_CS,
        .queue_size = 1,
    };
    spi_bus_add_device(LCD_HOST, &dev_cfg, &lcd_spi);

    lcd_cmd(0x11); vTaskDelay(120);
    lcd_cmd(0x36); lcd_data(0xa0);
    lcd_cmd(0x3A); lcd_data(0x55);
    lcd_cmd(0x21);
    lcd_cmd(0x29);
}

void lcd_show_image(const uint16_t *img) {
    static uint16_t line_buf[IMAGE_W] __attribute__((aligned(4)));
    lcd_set_window(0, 0, IMAGE_W - 1, IMAGE_H - 1);
    gpio_set_level(LCD_DC, 1);
    spi_transaction_t t = { .length = IMAGE_W * 16, .tx_buffer = line_buf };
    for (int y = 0; y < IMAGE_H; y++) {
        memcpy(line_buf, img + y * IMAGE_W, IMAGE_W * 2);
        spi_device_transmit(lcd_spi, &t);
    }
}

void lcd_show_image_fast(const uint16_t *img) {
    lcd_set_window(0, 0, IMAGE_W - 1, IMAGE_H - 1);
    gpio_set_level(LCD_DC, 1);
    for (int y = 0; y < IMAGE_H; y += 4) {
        int n = (y + 4 <= IMAGE_H) ? 4 : (IMAGE_H - y);
        spi_transaction_t t = {
            .length = IMAGE_W * n * 16,
            .tx_buffer = img + y * IMAGE_W,
        };
        spi_device_polling_transmit(lcd_spi, &t);
    }
}

static void find_motion(const uint16_t *prev, const uint16_t *curr,
                         int *out_dx, int *out_dy) {
    int best_sad = 0x7FFFFFFF;
    *out_dx = 0;
    *out_dy = 0;
        for (int dy = -3; dy <= 3; dy++) {
        for (int dx = -3; dx <= 3; dx++) {
            int sad = 0;
            for (int y = 10; y < IMAGE_H; y += 60) {
                int sy = y + dy;
                if (sy < 0 || sy >= IMAGE_H) continue;
                for (int x = 20; x < IMAGE_W; x += 60) {
                    int sx = x + dx;
                    if (sx < 0 || sx >= IMAGE_W) continue;
                    uint16_t a = prev[sy * IMAGE_W + sx];
                    uint16_t b = curr[y * IMAGE_W + x];
                    sad += abs((int)(a & 0xFF) - (int)(b & 0xFF)) +
                           abs((int)(a >> 8) - (int)(b >> 8));
                }
            }
            if (sad < best_sad) {
                best_sad = sad;
                *out_dx = dx;
                *out_dy = dy;
            }
        }
    }
}

void lcd_show_image_blend(const uint16_t *img_a, const uint16_t *img_b,
                           int mdx, int mdy) {
    static uint16_t line_buf[IMAGE_W * 4] __attribute__((aligned(4)));
    lcd_set_window(0, 0, IMAGE_W - 1, IMAGE_H - 1);
    gpio_set_level(LCD_DC, 1);

    if (mdx == 0 && mdy == 0) {
        for (int y = 0; y < IMAGE_H; y += 4) {
            int n = (y + 4 <= IMAGE_H) ? 4 : (IMAGE_H - y);
            for (int line = 0; line < n; line++) {
                const uint16_t *pa = img_a + (y + line) * IMAGE_W;
                const uint16_t *pb = img_b + (y + line) * IMAGE_W;
                uint16_t *dst = line_buf + line * IMAGE_W;
                for (int x = 0; x < IMAGE_W; x++) {
                    uint16_t a = (pa[x] >> 8) | (pa[x] << 8);
                    uint16_t b = (pb[x] >> 8) | (pb[x] << 8);
                    uint32_t r  = (((a >> 11) & 0x1F) + ((b >> 11) & 0x1F) * 3) >> 2;
                    uint32_t g  = (((a >> 5)  & 0x3F) + ((b >> 5)  & 0x3F) * 3) >> 2;
                    uint32_t bl = (( a        & 0x1F) + ( b        & 0x1F) * 3) >> 2;
                    uint16_t out = (uint16_t)((r << 11) | (g << 5) | bl);
                    dst[x] = (out >> 8) | (out << 8);
                }
            }
            spi_transaction_t t = { .length = IMAGE_W * n * 16, .tx_buffer = line_buf };
            spi_device_polling_transmit(lcd_spi, &t);
        }
        return;
    }

    int ol_start = (mdx > 0) ? mdx : 0;
    int ol_end   = (mdx < 0) ? (IMAGE_W + mdx) : IMAGE_W;

    for (int y = 0; y < IMAGE_H; y += 4) {
        int n = (y + 4 <= IMAGE_H) ? 4 : (IMAGE_H - y);
        for (int line = 0; line < n; line++) {
            int row = y + line;
            int ay = row - mdy;
            uint16_t *dst = line_buf + line * IMAGE_W;
            if (ay < 0 || ay >= IMAGE_H) {
                memcpy(dst, img_b + row * IMAGE_W, IMAGE_W * 2);
            } else {
                const uint16_t *ra = img_a + ay * IMAGE_W;
                const uint16_t *rb = img_b + row * IMAGE_W;
                for (int x = 0; x < ol_start; x++)
                    dst[x] = rb[x];
                for (int x = ol_start; x < ol_end; x++) {
                    uint16_t av = (ra[x - mdx] >> 8) | (ra[x - mdx] << 8);
                    uint16_t bv = (rb[x] >> 8) | (rb[x] << 8);
                    uint32_t r  = (((av >> 11) & 0x1F) + ((bv >> 11) & 0x1F) * 3) >> 2;
                    uint32_t g  = (((av >> 5)  & 0x3F) + ((bv >> 5)  & 0x3F) * 3) >> 2;
                    uint32_t bl = (( av        & 0x1F) + ( bv        & 0x1F) * 3) >> 2;
                    uint16_t out = (uint16_t)((r << 11) | (g << 5) | bl);
                    dst[x] = (out >> 8) | (out << 8);
                }
                for (int x = ol_end; x < IMAGE_W; x++)
                    dst[x] = rb[x];
            }
        }
        spi_transaction_t t = { .length = IMAGE_W * n * 16, .tx_buffer = line_buf };
        spi_device_polling_transmit(lcd_spi, &t);
    }
}

// ==================== Video Progress ====================
#define PROG_Y1     65
#define PROG_Y2     103
#define BAR_X1      30
#define BAR_X2      290
#define BAR_Y1      91
#define BAR_Y2      103

static const uint8_t prog_font[][8] = {
    {0x3C,0x42,0x42,0x42,0x42,0x42,0x3C,0x00}, // 0
    {0x10,0x30,0x50,0x10,0x10,0x10,0x7C,0x00}, // 1
    {0x3C,0x42,0x02,0x0C,0x30,0x40,0x7E,0x00}, // 2
    {0x3C,0x42,0x02,0x1C,0x02,0x42,0x3C,0x00}, // 3
    {0x08,0x18,0x28,0x48,0x7C,0x08,0x08,0x00}, // 4
    {0x7E,0x40,0x7C,0x02,0x02,0x42,0x3C,0x00}, // 5
    {0x1C,0x20,0x40,0x7C,0x42,0x42,0x3C,0x00}, // 6
    {0x7E,0x42,0x04,0x08,0x10,0x10,0x10,0x00}, // 7
    {0x3C,0x42,0x42,0x3C,0x42,0x42,0x3C,0x00}, // 8
    {0x3C,0x42,0x42,0x3E,0x02,0x04,0x38,0x00}, // 9
    {0x00,0x44,0x28,0x10,0x28,0x44,0x00,0x00}, // %
};

static int64_t last_prog_us = 0;


static void show_video_progress(int percent) {
    static uint16_t line[IMAGE_W];

    int64_t now = esp_timer_get_time();
    if (now - last_prog_us < 33333) return;
    last_prog_us = now;

    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;

    if (!screen_cleared) {
        memset(line, 0, IMAGE_W * 2);
        lcd_set_window(0, 0, IMAGE_W - 1, IMAGE_H - 1);
        gpio_set_level(LCD_DC, 1);
        for (int y = 0; y < IMAGE_H; y++) {
            spi_transaction_t t = { .length = IMAGE_W * 16, .tx_buffer = line };
            spi_device_polling_transmit(lcd_spi, &t);
        }
        screen_cleared = true;
    }

    char text[5];
    int tlen;
    if (percent >= 100)     { text[0]='1'; text[1]='0'; text[2]='0'; text[3]='%'; tlen=4; }
    else if (percent >= 10) { text[0]='0'+percent/10; text[1]='0'+percent%10; text[2]='%'; tlen=3; }
    else                    { text[0]='0'+percent; text[1]='%'; tlen=2; }

    int text_x0 = (IMAGE_W - tlen * 16) / 2;
    int text_y0 = 65;

    lcd_set_window(0, PROG_Y1, IMAGE_W - 1, PROG_Y2);
    gpio_set_level(LCD_DC, 1);

    for (int y = PROG_Y1; y <= PROG_Y2; y++) {
        memset(line, 0, IMAGE_W * 2);

        int fy = (y - text_y0) >> 1;
        if (fy >= 0 && fy < 8) {
            for (int ci = 0; ci < tlen; ci++) {
                int idx = (text[ci] == '%') ? 10 : (text[ci] - '0');
                if (idx < 0 || idx > 10) continue;
                uint8_t bits = prog_font[idx][fy];
                int cx = text_x0 + ci * 16;
                for (int fx = 0; fx < 8; fx++) {
                    if (bits & (0x80 >> fx)) {
                        int px = cx + fx * 2;
                        if (px >= 0 && px + 1 < IMAGE_W) {
                            line[px] = 0xFFFF;
                            line[px + 1] = 0xFFFF;
                        }
                    }
                }
            }
        }

        if (y >= BAR_Y1 && y <= BAR_Y2) {
            if (y == BAR_Y1 || y == BAR_Y2) {
                for (int x = BAR_X1; x <= BAR_X2; x++)
                    line[x] = 0xFFFF;
            } else {
                line[BAR_X1] = 0xFFFF;
                line[BAR_X2] = 0xFFFF;
                int fill = (BAR_X2 - BAR_X1 - 1) * percent / 100;
                for (int x = 1; x <= fill; x++)
                    line[BAR_X1 + x] = 0xFFFF;
            }
        }

        spi_transaction_t t = { .length = IMAGE_W * 16, .tx_buffer = line };
        spi_device_polling_transmit(lcd_spi, &t);
    }
}

// ==================== NVS ====================
void save_config(void) {
    nvs_handle_t h;
    if (nvs_open(NVS_CFG_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_u8(h, "mode", led_current_mode);
        nvs_set_u8(h, "r", target_r);
        nvs_set_u8(h, "g", target_g);
        nvs_set_u8(h, "b", target_b);
        nvs_set_u8(h, "bright", global_brightness);
        nvs_commit(h);
        nvs_close(h);
    }
}

void load_config(void) {
    nvs_handle_t h;
    if (nvs_open(NVS_CFG_NAMESPACE, NVS_READONLY, &h) == ESP_OK) {
        nvs_get_u8(h, "mode", &led_current_mode);
        nvs_get_u8(h, "r", &target_r);
        nvs_get_u8(h, "g", &target_g);
        nvs_get_u8(h, "b", &target_b);
        nvs_get_u8(h, "bright", &global_brightness);
        nvs_close(h);
    }
}

// ==================== BLE GAP ====================
static void start_advertising(void);

static int gap_event_cb(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                ble_conn_handle = event->connect.conn_handle;
                ESP_LOGI(TAG, "BLE connected");
            } else {
                start_advertising();
            }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            ESP_LOGI(TAG, "BLE disconnected");
            ble_conn_handle = BLE_HS_CONN_HANDLE_NONE;
            ack_subscribed = false;
            ble_img_receiving = false;
            ble_img_offset = 0;
            jpeg_receiving = false;
            jpeg_offset = 0;
            jpeg_expected = 0;
            video_receiving = false;
            video_state = 0;
            start_advertising();
            break;
        case BLE_GAP_EVENT_MTU:
            ESP_LOGI(TAG, "MTU: %d", event->mtu.value);
            break;
            case BLE_GAP_EVENT_SUBSCRIBE:
    if (event->subscribe.attr_handle == ack_chr_val_handle) {
        ack_subscribed = event->subscribe.cur_notify;
        ESP_LOGI(TAG, "ACK notify %s", ack_subscribed ? "ON" : "OFF");
    }
    break;
    }
    return 0;
}

static void start_advertising(void) {
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    int rc;

    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)ble_svc_gap_device_name();
    fields.name_len = strlen(ble_svc_gap_device_name());
    fields.name_is_complete = 1;

    static const ble_uuid16_t svc_uuid = BLE_UUID16_INIT(ZEROBOX_SVC_UUID);
    fields.uuids16 = &svc_uuid;
    fields.num_uuids16 = 1;
    fields.uuids16_is_complete = 1;

    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) return;

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER,
                      &adv_params, gap_event_cb, NULL);
}

// ==================== BLE GATT ====================
static int cmd_access_cb(uint16_t ch, uint16_t ah,
                          struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return 0;

    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    char buf[128];
    if (len > sizeof(buf) - 1) len = sizeof(buf) - 1;
    ble_hs_mbuf_to_flat(ctxt->om, buf, len, NULL);
    buf[len] = '\0';

    ESP_LOGI(TAG, "CMD: %s", buf);

        if (strstr(buf, "mode1")) {
        led_current_mode = LED_MODE_ON;
        audio_active = false;
        save_config();
    }
    if (strstr(buf, "mode2")) {
        led_current_mode = LED_MODE_BREATH;
        audio_active = false;
        save_config();
    }
    if (strstr(buf, "mode3")) {
        led_current_mode = LED_MODE_PULSE;
        audio_active = false;
        save_config();
    }
    if (strstr(buf, "AUDIO:ON")) {
        audio_active = true;
        led_current_mode = LED_MODE_AUDIO;
    }
    
    if (strstr(buf, "COLOR:")) {
        int r, g, b;
        if (sscanf(buf, "COLOR:%d,%d,%d", &r, &g, &b) == 3) {
            target_r = r; target_g = g; target_b = b;
            save_config();
        }
    }
    if (strstr(buf, "BRIGHT:")) {
        int b;
        if (sscanf(buf, "BRIGHT:%d", &b) == 1) {
            global_brightness = (b > 255) ? 255 : ((b < 0) ? 0 : b);
            save_config();
        }
    }
    return 0;
}

static int ack_access_cb(uint16_t ch, uint16_t ah,
                           struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t val = 0;
        os_mbuf_append(ctxt->om, &val, 1);
        return 0;
    }
    return 0;
}

static int data_access_cb(uint16_t ch, uint16_t ah,
                           struct ble_gatt_access_ctxt *ctxt, void *arg) {
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return 0;

    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    uint8_t tmp[256];
    ble_hs_mbuf_to_flat(ctxt->om, tmp, len, NULL);

    // ========== AUDIO DATA ==========
    if (len == 9 && tmp[0] == 0xAA) {
        audio_level = tmp[1];
        for (int i = 0; i < 7; i++) {
            audio_bands[i] = tmp[2 + i];
        }
        return 0;
    }

    // ========== IMAGE_JPEG ==========
    if (!jpeg_receiving && !video_receiving && !ble_img_receiving &&
        len == 10 && memcmp(tmp, "IMAGE_JPEG", 10) == 0) {
        jpeg_offset = 0;
        jpeg_expected = 0;
        jpeg_receiving = true;
        playback_active = false;
        ESP_LOGI(TAG, "JPEG receive start");
        return 0;
    }

    if (jpeg_receiving && jpeg_expected == 0 && len >= 4) {
        jpeg_expected = (tmp[0]<<24)|(tmp[1]<<16)|(tmp[2]<<8)|tmp[3];
        ESP_LOGI(TAG, "JPEG size: %d", jpeg_expected);
        if (len > 4) {
            memcpy(jpeg_buf, tmp + 4, len - 4);
            jpeg_offset = len - 4;
        }
        return 0;
    }

    if (jpeg_receiving && jpeg_expected > 0) {
        if (jpeg_offset + len > JPEG_BUF_SIZE) {
            jpeg_receiving = false;
            jpeg_offset = 0;
            jpeg_expected = 0;
            return 0;
        }
        memcpy(jpeg_buf + jpeg_offset, tmp, len);
        jpeg_offset += len;
        if (jpeg_offset >= jpeg_expected) {
            jpeg_receiving = false;
            ESP_LOGI(TAG, "JPEG done: %d bytes", jpeg_offset);
            xSemaphoreGive(jpeg_decode_sem);
        }
        return 0;
    }

    // ========== VIDEO_START ==========
    if (!video_receiving && !jpeg_receiving && !ble_img_receiving &&
        len == 11 && memcmp(tmp, "VIDEO_START", 11) == 0) {
        video_receiving = true;
        video_state = 0;
        video_total_frames = 0;
        video_current_frame = 0;
        video_frame_offset = 0;
        playback_active = false;

        esp_flash_erase_region(NULL, MEDIA_FLASH_ADDR, MEDIA_SECTOR_SIZE);
        video_flash_addr = MEDIA_DATA_START;
        video_next_erase = MEDIA_DATA_START;

        ESP_LOGI(TAG, "VIDEO receive start");
        send_ack(0x01);
        video_progress_percent = 0;
        return 0;
    }

    if (video_receiving) {
        if (video_state == 0 && len == 4) {
            video_total_frames = (tmp[0]<<24)|(tmp[1]<<16)|(tmp[2]<<8)|tmp[3];
            if (video_total_frames <= 0 || video_total_frames > MAX_VIDEO_FRAMES) {
                ESP_LOGE(TAG, "Invalid frame count: %d, abort", video_total_frames);
                video_receiving = false;
                return 0;
            }
            ESP_LOGI(TAG, "VIDEO frames: %d", video_total_frames);
            video_state = 1;
            send_ack(0x02);
            return 0;
        }

        if (video_state == 1 && len == 4) {
            video_frame_size = (tmp[0]<<24)|(tmp[1]<<16)|(tmp[2]<<8)|tmp[3];
            video_frame_offset = 0;
            video_state = 2;
            return 0;
        }

        if (video_state == 2) {
            if (video_frame_offset + len > JPEG_BUF_SIZE) {
                ESP_LOGE(TAG, "Frame %d too large, skip", video_current_frame);
                video_current_frame++;
                video_progress_percent = video_current_frame * 100 / video_total_frames;
                if (video_current_frame >= video_total_frames) {
                    video_receiving = false;
                    flash_write_item_t fin = { .data = NULL, .len = 0 };
                    xQueueSend(flash_queue, &fin, portMAX_DELAY);
                    ESP_LOGI(TAG, "VIDEO: all %d frames queued", video_total_frames);
                } else {
                    video_state = 1;
                }
                return 0;
            }

            memcpy(jpeg_buf + video_frame_offset, tmp, len);
            video_frame_offset += len;

            if (video_frame_offset >= video_frame_size) {
                video_frame_sizes[video_current_frame] = video_frame_size;

                uint8_t *frame_copy = malloc(video_frame_size);
                if (frame_copy) {
                    memcpy(frame_copy, jpeg_buf, video_frame_size);
                    flash_write_item_t item = { .data = frame_copy, .len = video_frame_size };
                    if (xQueueSend(flash_queue, &item, 0) != pdTRUE) {
                        ESP_LOGE(TAG, "Flash queue full, drop frame %d", video_current_frame);
                        free(frame_copy);
                    }
                }

                ESP_LOGI(TAG, "Frame %d: %d bytes -> queue",
                         video_current_frame, video_frame_size);
                video_current_frame++;
                video_progress_percent = video_current_frame * 100 / video_total_frames;
                if (video_current_frame >= video_total_frames) {
                    video_receiving = false;
                    flash_write_item_t fin = { .data = NULL, .len = 0 };
                    xQueueSend(flash_queue, &fin, portMAX_DELAY);
                    ESP_LOGI(TAG, "VIDEO: all %d frames queued", video_total_frames);
                } else {
                    video_state = 1;
                }

                
            }
            return 0;
        }
    }

    // ========== IMAGE_START ==========
    if (!ble_img_receiving && !jpeg_receiving && !video_receiving && len == 11) {
        char cmd[12];
        ble_hs_mbuf_to_flat(ctxt->om, cmd, len, NULL);
        cmd[len] = '\0';
        if (memcmp(cmd, "IMAGE_START", 11) == 0) {
            ble_img_offset = 0;
            ble_img_receiving = true;
            playback_active = false;
            return 0;
        }
    }

    if (ble_img_receiving) {
        if (ble_img_offset + len > IMAGE_SIZE) {
            ble_img_receiving = false;
            ble_img_offset = 0;
            return 0;
        }
        ble_hs_mbuf_to_flat(ctxt->om, g_image_data + ble_img_offset, len, NULL);
        ble_img_offset += len;
        if (ble_img_offset >= IMAGE_SIZE) {
            ble_img_receiving = false;
            xSemaphoreGive(img_save_sem);
        }
    }

    return 0;
}

// ==================== BL 服务定义 ====================
static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(ZEROBOX_SVC_UUID),
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = BLE_UUID16_DECLARE(ZEROBOX_CMD_UUID),
                .access_cb = cmd_access_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = BLE_UUID16_DECLARE(ZEROBOX_DATA_UUID),
                .access_cb = data_access_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = BLE_UUID16_DECLARE(ZEROBOX_ACK_UUID),
                .access_cb = ack_access_cb,
                .val_handle = &ack_chr_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
            },
            {0}
        },
    },
    {0}
};

// ==================== BLE 启动 ====================
static void ble_on_reset(int reason) { ESP_LOGE(TAG, "BLE reset: %d", reason); }

static void ble_on_sync(void) {
    ble_hs_id_infer_auto(0, &ble_addr_type);
    ble_att_set_preferred_mtu(247);
    uint8_t addr[6];
    ble_hs_id_copy_addr(ble_addr_type, addr, NULL);
    ESP_LOGI(TAG, "BLE addr: %02x:%02x:%02x:%02x:%02x:%02x",
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    start_advertising();
    ESP_LOGI(TAG, "Advertising as \"%s\"", BLE_DEVICE_NAME);
}

static void ble_host_task(void *param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

// ==================== 图片处理任务 ====================
static void img_save_task(void *arg) {
    while (1) {
        if (xSemaphoreTake(jpeg_decode_sem, 0) == pdTRUE) {
            int64_t t0 = esp_timer_get_time();
            esp_err_t err = decode_jpeg_to_rgb565(jpeg_buf, jpeg_expected,
                                                   g_image_data, IMAGE_SIZE);
            if (err == ESP_OK) {
                ESP_LOGI(TAG, "Decode: %lld ms", (esp_timer_get_time() - t0) / 1000);
                lcd_show_image_fast((uint16_t *)g_image_data);
                save_image_to_flash(g_image_data, IMAGE_SIZE);
            }
        }

        if (xSemaphoreTake(img_save_sem, 0) == pdTRUE) {
            lcd_show_image((uint16_t *)g_image_data);
            save_image_to_flash(g_image_data, IMAGE_SIZE);
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

// ==================== 视频解码任务0 ====================
static void video_decode_task(void *arg) {
    while (1) {
        if (!playback_active || !has_stored_video || stored_video_frames == 0) {
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }
        ESP_LOGI(TAG, "PLAYBACK START: %d frames", stored_video_frames);
        int64_t next_frame_us = 0;
        int64_t loop_t0 = esp_timer_get_time();
        int64_t sum_read = 0, sum_dec = 0, sum_disp = 0;
        int count = 0;

        for (int i = 0; i < stored_video_frames; i++) {
            if (!playback_active) break;

            int buf;
            xQueueReceive(buf_free_queue, &buf, portMAX_DELAY);

            uint32_t frame_addr = MEDIA_DATA_START + stored_frame_offsets[i];
            uint32_t frame_size = stored_frame_sizes[i];
            if (frame_size == 0) {
                xQueueSend(buf_free_queue, &buf, 0);
                continue;
            }

            if (next_frame_us == 0) next_frame_us = esp_timer_get_time();
            while (esp_timer_get_time() < next_frame_us) { asm volatile("nop"); }
            next_frame_us += 47619;

            int64_t t0 = esp_timer_get_time();
            esp_flash_read(NULL, jpeg_buf, frame_addr, frame_size);
            int64_t t1 = esp_timer_get_time();
            decode_jpeg_to_rgb565(jpeg_buf, frame_size, vbuf[buf], IMAGE_SIZE);
            int64_t t2 = esp_timer_get_time();

            sum_read += (t1 - t0);
            sum_dec  += (t2 - t1);
            count++;
            sum_disp += last_display_us;

            xQueueSend(display_queue, &buf, portMAX_DELAY);

            int64_t elapsed = (esp_timer_get_time() - loop_t0) / 1000;
            if (elapsed >= 1000) {
                float fps = count * 1000.0f / elapsed;
                ESP_LOGI(TAG, "STAT: %.1f fps | read: %lld ms | dec: %lld ms | disp: %lld ms | frames: %d",
                         fps, sum_read / count / 1000, sum_dec / count / 1000,
                         sum_disp / count / 1000, count);
                loop_t0 = esp_timer_get_time();
                sum_read = sum_dec = sum_disp = 0;
                count = 0;
            }
        }
        ESP_LOGI(TAG, "PLAYBACK END: %lld ms",
                 (esp_timer_get_time() - loop_t0) / 1000);
        int leftover;
        while (xQueueReceive(display_queue, &leftover, 0) == pdTRUE)
            xQueueSend(buf_free_queue, &leftover, 0);
    }
}

// ==================== 视频显示任务1 ====================
static void video_display_task(void *arg) {
    int held_buf = -1;
    int smooth_dx = 0, smooth_dy = 0;
    disp_log_t0 = esp_timer_get_time();
    disp_direct_count = 0;
    disp_blend_count = 0;

    while (1) {
        int buf;
        int timeout = (video_progress_percent >= 0) ? 50 : 500;
        if (xQueueReceive(display_queue, &buf, pdMS_TO_TICKS(timeout)) != pdTRUE) {
            if (held_buf >= 0) {
                xQueueSend(buf_free_queue, &held_buf, 0);
                held_buf = -1;
            }
            if (video_progress_percent >= 0) {
                show_video_progress(video_progress_percent);
            }
            continue;
        }

        int64_t t0 = esp_timer_get_time();

        lcd_show_image_fast((uint16_t *)vbuf[buf]);
        disp_direct_count++;

        if (held_buf >= 0) {
            int raw_dx = 0, raw_dy = 0;
            find_motion((uint16_t *)vbuf[held_buf], (uint16_t *)vbuf[buf],
                        &raw_dx, &raw_dy);
            smooth_dx = (smooth_dx * 3 + raw_dx) / 4;
            smooth_dy = (smooth_dy * 3 + raw_dy) / 4;
                        
            lcd_show_image_blend((uint16_t *)vbuf[held_buf],
                                  (uint16_t *)vbuf[buf],
                                  smooth_dx, smooth_dy);
            disp_blend_count++;

            xQueueSend(buf_free_queue, &held_buf, portMAX_DELAY);
        }

        held_buf = buf;
        if (video_progress_percent >= 0) {
            show_video_progress(video_progress_percent);
        }

        last_display_us = esp_timer_get_time() - t0;

        int64_t elapsed = (esp_timer_get_time() - disp_log_t0) / 1000;
        if (elapsed >= 1000) {
            float fps = (disp_direct_count + disp_blend_count) * 1000.0f / elapsed;
            ESP_LOGI(TAG, "DISP: %.1f fps | direct: %d | blend: %d",
                     fps, disp_direct_count, disp_blend_count);
            disp_log_t0 = esp_timer_get_time();
            disp_direct_count = 0;
            disp_blend_count = 0;
        }
    }
}

// ==================== LED ====================
static void ws2812_init(void) {
    led_strip_config_t strip_cfg = {
        .strip_gpio_num = WS2812_GPIO_NUM,
        .max_leds = LED_NUM,
    };
    led_strip_rmt_config_t rmt_cfg = { .resolution_hz = 10 * 1000 * 1000 };
    led_strip_new_rmt_device(&strip_cfg, &rmt_cfg, &led_strip);
    led_strip_clear(led_strip);
}

static void led_task(void *pvParameters) {
    ws2812_init();
    uint8_t phase = 0;
    static uint8_t audio_peaks[LED_NUM] = {0};

    while (1) {
        if (led_current_mode != prev_mode) { phase = 0; prev_mode = led_current_mode; }

        if (led_current_mode == LED_MODE_ON) {
            uint32_t r = (target_r * global_brightness) / 255;
            uint32_t g = (target_g * global_brightness) / 255;
            uint32_t b = (target_b * global_brightness) / 255;
            for (int i = 0; i < LED_NUM; i++) led_strip_set_pixel(led_strip, i, r, g, b);
            led_strip_refresh(led_strip);
            vTaskDelay(pdMS_TO_TICKS(100));

                                    } else if (led_current_mode == LED_MODE_AUDIO) {
            for (int i = 0; i < LED_NUM; i++) {
                uint8_t band = (audio_bands[i] > 255) ? 255 : (uint8_t)audio_bands[i];
                uint8_t mapped = audio_lut[band * 75 / 255];
                uint32_t r = (target_r * mapped) / 255;
                uint32_t g = (target_g * mapped) / 255;
                uint32_t b = (target_b * mapped) / 255;
                led_strip_set_pixel(led_strip, i, r, g, b);
            }
            led_strip_refresh(led_strip);
        }
    }
}

// ==================== Flash 写入任务 ====================
static void flash_write_task(void *arg) {
    flash_write_item_t item;
    int wr_count = 0;
    while (1) {
        if (xQueueReceive(flash_queue, &item, pdMS_TO_TICKS(10000)) == pdTRUE) {
            if (item.data == NULL) {
                ESP_LOGI(TAG, "FLASH_WR: all %d frames written, finalizing", wr_count);
                finalize_video_save(wr_count);
                playback_active = true;
                ESP_LOGI(TAG, "VIDEO complete, playback started");
                wr_count = 0;
                continue;
            }
            if (item.len > 0) {
                uint32_t end = video_flash_addr + item.len;
                while (video_next_erase < end) {
                    esp_flash_erase_region(NULL, video_next_erase, MEDIA_SECTOR_SIZE);
                    video_next_erase += MEDIA_SECTOR_SIZE;
                }
                esp_flash_write(NULL, item.data, video_flash_addr, item.len);
                video_flash_addr += item.len;
                free(item.data);
                wr_count++;
                send_ack(0x03);
            }
        } else {
            if (video_receiving && wr_count > 0) {
                ESP_LOGW(TAG, "FLASH_WR: timeout, %d/%d frames, saving anyway",
                         wr_count, video_total_frames);
                video_receiving = false;
                finalize_video_save(wr_count);
                playback_active = true;
                ESP_LOGI(TAG, "VIDEO complete (partial), playback started");
                wr_count = 0;
            }
        }
    }
}

// ==================== 主函数 ====================
void app_main(void) {
	esp_log_level_set("spi_master", ESP_LOG_NONE);  // 最前面
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }

    ESP_LOGI(TAG, "ZERO_BOX START");

    lcd_init();
    esp_log_level_set("spi_master", ESP_LOG_WARN);
    load_media_from_flash();
    load_config();

    if (g_image_valid && !has_stored_video) {
        lcd_show_image((uint16_t *)g_image_data);
    }

    if (has_stored_video) {
        playback_active = true;
    }

    nimble_port_init();
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 0;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 0;
    ble_hs_cfg.sm_our_key_dist = 0;
    ble_hs_cfg.sm_their_key_dist = 0;
    ble_hs_cfg.reset_cb = ble_on_reset;
    ble_hs_cfg.sync_cb = ble_on_sync;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svcs);
    ble_gatts_add_svcs(gatt_svcs);
    ble_svc_gap_device_name_set(BLE_DEVICE_NAME);

    img_save_sem = xSemaphoreCreateBinary();
    jpeg_decode_sem = xSemaphoreCreateBinary();
    flash_queue = xQueueCreate(16, sizeof(flash_write_item_t));
    xTaskCreatePinnedToCore(flash_write_task, "flash_wr", 4096, NULL, 3, NULL, 1);
    xTaskCreatePinnedToCore(img_save_task, "img_save", 8192, NULL, 3, NULL, 1);

    vbuf[0] = heap_caps_malloc(IMAGE_SIZE, MALLOC_CAP_SPIRAM);
    vbuf[1] = heap_caps_malloc(IMAGE_SIZE, MALLOC_CAP_SPIRAM);
    vbuf[2] = heap_caps_malloc(IMAGE_SIZE, MALLOC_CAP_SPIRAM);
    vbuf[3] = heap_caps_malloc(IMAGE_SIZE, MALLOC_CAP_SPIRAM);
    if (vbuf[0] && vbuf[1] && vbuf[2] && vbuf[3]) {
        display_queue = xQueueCreate(4, sizeof(int));
        buf_free_queue = xQueueCreate(4, sizeof(int));
        for (int i = 0; i < 4; i++) xQueueSend(buf_free_queue, &i, 0);
        xTaskCreatePinnedToCore(video_decode_task, "vid_dec", 4096, NULL, 5, NULL, 0);
        xTaskCreatePinnedToCore(video_display_task, "vid_disp", 4096, NULL, 4, NULL, 1);
    }
    
    xTaskCreatePinnedToCore(led_task, "led_task", 4096, NULL, 3, NULL, 1);
    nimble_port_freertos_init(ble_host_task);
}