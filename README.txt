项目简介

适配30×65mm sbc的rgb+副屏海景房机箱
android设备通过app与ble蓝牙控制灯效与副屏
7颗ws2812支持三种灯效，通过android设备外录与内录实现频谱拾音灯
st7789 1.47寸 320×172副屏支持显示图片与播放视频
视频最高70s 21fps@rgb565，插帧后可达42fps
机箱两侧设有风扇主动散热

技术实现

基于esp32 s3 n16r8  st7789  ws2812  rtos  android15
双核四缓冲流水线，块匹配运动补偿+加权混色插帧，dma行扫描刷新，flash增量擦除顺序写入
gamma曲线呼吸，脉冲灯效，频谱灯低通滤波，自定义gamma lut(解决ws2812高亮度变化小问题)
nim ble零配对mtu 247，三通道分离指令/数据/回执，状态机分流，视频帧异步落flash，ack回执流控
外录+内录双音源切换，实时FFT频谱分析，7频段dBFS归一化+自动对比度拉伸
MediaCodec解码+自定义裁剪区域，逐帧JPEG压缩，本地存储预览+异步上传

开源协议

CC BY-NC 4.0
Copyright (c) 2026 6567676
You are free to:
  - Share and redistribute in any medium or format
  - Remix, transform, and build upon the material
Under the following terms:
  - NonCommercial only
Full license: https://creativecommons.org/licenses/by-nc/4.0/

其他

此项目仅在瑞莎zero 3w，hyperOS 3完成验证1