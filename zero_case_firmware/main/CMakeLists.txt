idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    REQUIRES driver freertos nvs_flash esp_partition led_strip bt esp_timer
    PRIV_REQUIRES esp_jpeg
)