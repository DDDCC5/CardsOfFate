# 命运之牌 · Cards of Fate

PICO Spatial 平台的 MR 塔罗体验应用。包名 `com.example.cardsfate`，系统为中文时显示「命运之牌」，其他语言显示「Cards of Fate」，运行界面按语言单独显示。

**当前状态：1.0.1 调试预览版，完整实机验收待完成。** [下载 APK](https://github.com/DDDCC5/CardsOfFate/releases/tag/v1.0.1-debug) · [投稿资料](docs/submission/填写指南.md)

## 体验与实现

- 78张韦特塔罗牌环绕展示，翻背合围、手势聚拢、双次交错洗牌、三牌选择和过去/现在/未来解读。
- 使用官方手部关节、眼动及头部追踪；眼动不可用时支持手指指向与捏合。
- 未揭示牌面节点禁用；翻牌按实际手部姿态更新，正逆位随机概率50%。
- 中文/英文独立牌面、文案和本地混响旁白；三牌模板总结；保存最近10次记录。
- 浮空水晶、双蜡烛、旋转星盘和24个符文；自定义暗色金线界面和分层启动动画。
- 预留大模型解读、其他塔罗体系和付费接口；当前版本无在线生成或购买流程。

## 验证范围与运行前提

构建和34项单元测试通过。实机已显示自定义中文界面，官方手部与眼动均返回 SUPPORTED；眼动权限由用户允许。

本轮设备的平面服务处于 RUNNING，但读取到0个系统平面。当前调试版会等待可用空间数据，再开放「开始仪式」；不绕过空间避让检查，不新增应用内扫描引导。请先在头显系统准备当前房间的空间数据，再点击「重试」。

完整洗牌/抽牌/翻牌流程、手势阈值、透视暗层效果、持续性能和逐屏视觉一致性仍待实机验收。避让只覆盖系统已知几何；公开SDK接口不能强制切换系统输入模式，手柄提示基于追踪状态及姿态活动。

所有解读仅供娱乐和自我思考参考。

## 构建

- Kotlin + SpatialUI Compose + PICO Spatial SDK BOM 6.1.9。
- JDK21、Android SDK35、arm64-v8a。需要 PICO Spatial 运行环境；普通 Android 手机不具备对应空间服务。
- 配置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir` 后运行：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
python3 tools/verify_runtime_package.py
```

APK输出：`app/build/outputs/apk/debug/app-debug.apk`。运行素材已包含，无需模型生成服务或API密钥。源码中有两个约78MB牌库文件，完整克隆会下载约400MB素材。

## 目录

- `app/src/main/java/com/example/cardsfate/`：空间场景、追踪、领域状态、音频、仓库和界面。
- `app/src/main/assets/`：9个GLB文件、1262段旁白、14个音效、文本和许可。
- `app/src/test/`：占卜状态机、手势分类、历史裁剪、语言及音频回调测试。
- `docs/submission/`：按Vibe Lab字段整理的投稿文案与素材说明。

本仓库仅发布应用工程与运行所需素材；不包含本机配置、SDK二进制、插件实现、生产虚拟环境和真实房间截图。第三方来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。公开代码未额外指定统一开源许可证；第三方内容各自遵循其原始许可。

## 1.0.1 图标修正

Android兼容图标与PICO空间图标均打包为确认的塔罗标记，删除模板前景图层；适配图标资源同步更新。编译后的PNG像素与源资源逐一比对通过，资源数组只引用正式图层。[APK图标核对报告](docs/submission/icon-package-verification.json)。
