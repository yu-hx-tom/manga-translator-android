# 模型与第三方说明

## 运行依赖

- ONNX Runtime Android 1.22.0，`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`，MIT：[官方许可](https://github.com/microsoft/onnxruntime/blob/v1.22.0/LICENSE)。通过 Maven 获取，未把 AAR/JAR 复制到本仓库。
- AndroidX WebKit 1.12.1，`androidx.webkit:webkit:1.12.1`，Apache-2.0：[官方组件元数据](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1.pom)。使用系统 WebView，不包含额外浏览器内核。
- Gradle Wrapper 9.4.1，Apache-2.0：[Gradle 许可](https://github.com/gradle/gradle/blob/v9.4.1/LICENSE)。Wrapper 用于获取固定版本的构建工具。

## 检测模型不随仓库分发

当前代码只启用 PP-OCR 分块检测路径。原个人测试权重来源为 [Manga Translator Android v3.5.5 发布页](https://github.com/jedzqer/manga-translator-android/releases/tag/v3.5.5) 的 APK 中 `assets/models/detection/PP-OCRv6_det_mobile_infer.onnx`，本项目运行时名称为 `detector.onnx`。

- 文件大小：9,880,512 字节。
- SHA-256：`d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`。
- 独立再分发授权尚未核验。本仓库不提供权重、第三方 APK 或自动提取工具；以上信息仅用于说明历史来源及当前兼容性要求，不能当作许可证明。
- 构建者须自行确认权重的取得及使用权限。更换为其他合法模型时，需要调整并验证 `DetectorModels.java` 和检测器的张量处理逻辑。

源码中保留部分 RT-DETR 相关算法类，但当前设置仅开放 PP-OCR。历史研究参考：[comic-translate](https://github.com/ogkalu2/comic-translate/tree/8977b91a4f7a40c3917c5a268e9e7d78e1d818da) 与 [comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector/tree/16e8a622f91fabc6b5b65c96d32d1183f8843546)。没有分发其权重或历史研究文件。
