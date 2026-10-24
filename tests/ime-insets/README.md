# 外屏键盘边距回归验证

本测试在连接手机的真实 Android 框架上运行，使用 MIX Flip 2 / OS3.0.304.0.WOHCNXM 抓取的窗口尺寸。它不安装应用、不修改设置，也不需要 Root。

验证的故障条件：默认兼容模式下缩放比例为 0.8；反向外屏应用窗口为 `[399,0][1410,1740]`，缩放后的摄像头边距为 `[0,0][498,1740]`。小米的 `WindowInsets.consumeCutout()` 清除了内容边距，但可见边距仍然保留 99px 的左侧避让，触发 `ViewRootImpl.scrollToRectOrFocus()` 的额外平移。

测试确认清理后的两种边距一致、键盘高度仍为 912px、原始共享状态未被修改，并检查正常方向不存在这项偏差。该测试验证系统边距计算；模块注入、开关范围和 Telegram 最终画面仍需安装模块后实机验证。

PowerShell 运行示例（修改 SDK 路径与版本以匹配本机）：

```powershell
$sdkPath = 'C:/Users/Yadomin/AppData/Local/Android/Sdk'
New-Item -ItemType Directory -Force build/ime-insets-test | Out-Null
javac -classpath "$sdkPath/platforms/android-36/android.jar" -d build/ime-insets-test tests/ime-insets/ImeInsetsRegression.java
& "$sdkPath/build-tools/36.0.0/d8.bat" --lib "$sdkPath/platforms/android-36/android.jar" --output build/ime-insets-test/regression.jar build/ime-insets-test/ImeInsetsRegression.class
adb push build/ime-insets-test/regression.jar /data/local/tmp/mixflipmod-ime-regression.jar
adb shell 'CLASSPATH=/data/local/tmp/mixflipmod-ime-regression.jar app_process /system/bin ImeInsetsRegression'
```

预期输出五项 `PASS`。适用于含小米 `consumeCutout()` 扩展的系统，其他系统可能没有该隐藏接口。

界面回归：开启“系统框架 → 修复外屏键盘弹出时画面上移”，重启进入新模块后，在默认模式的 Telegram 聊天中分别检查摄像头朝右下、左上时打开/关闭键盘、键盘打开时旋转、切换聊天以及表情面板。还需确认内屏、全屏模式及关闭开关时的行为。

## 2026-10-24 实机结果

- MIX Flip 2、Android 16、HyperOS OS3.0.304.0.WOHCNXM、搜狗输入法小米版、Telegram 默认渲染模式。
- 替换模块、启用修复并重启后，摄像头左上方向的全宽键盘上方已恢复输入框、聊天内容及顶部栏。
- 应用窗口仍为 `[399,0][1410,1740]`，客户端 IME 边距仍为 `[0,828][1510,1740]`，残留的 displayCutout source 已消失；本次没有改变窗口缩放及键盘高度。
- 已通过 `:app:assembleDebug`、`:app:lintDebug` 和上述五项框架计算断言。
- 开关关闭、内屏、其他渲染模式及表情面板等完整回归尚未验证。

## Gboard 摄像头避让

`GboardWindowRegression.java` 在手机的真实 `WindowLayout.computeFrames()` 上验证摄像头避让。使用本机外屏的 1208×1392 尺寸与右侧 398px 安全边距，对比原始参数和加入 `displayCutout` 避让后的窗口。八项断言检查右下方向的侧边避让、左上方向保留完整窗口尺寸并清理左侧边距、无摄像头边距时布局不变，以及原始参数和共享状态未被修改。

```powershell
javac -classpath "$sdkPath/platforms/android-36/android.jar" -d build/ime-insets-test tests/ime-insets/GboardWindowRegression.java
& "$sdkPath/build-tools/36.0.0/d8.bat" --lib "$sdkPath/platforms/android-36/android.jar" --output build/ime-insets-test/gboard-regression.jar build/ime-insets-test/GboardWindowRegression.class
adb push build/ime-insets-test/gboard-regression.jar /data/local/tmp/mixflipmod-gboard-regression.jar
adb shell 'CLASSPATH=/data/local/tmp/mixflipmod-gboard-regression.jar app_process /system/bin GboardWindowRegression'
```

预期输出八项 `PASS`。需要支持小米窗口扩展的手机；该测试验证窗口几何，不覆盖 Gboard 内部接口定位和原生键盘高度。实际按键、候选词、表情和浮动模式仍需单独检查。

设置入口为“Gboard → 外屏摄像头避让”，沿用原来的设置键，移动入口不会重置开关。启用时会请求系统框架和 Gboard 两个作用域。系统框架部分仅处理合盖、默认显示屏上的 Gboard 输入法主窗口：右下方向按摄像头边距收窄；左上方向保留完整窗口尺寸并清理残留边距。Gboard 部分通过稳定配置字符串定位其内部接口，在左上方向取消自身的摄像头侧边留白并限制原生键盘高度。展开后或关闭开关时保留原有布局逻辑。

2026-10-24 实机验证：MIX Flip 2 / OS3.0.304.0.WOHCNXM，Gboard `18.5.4.993608717-beta-arm64-v8a`。启用两个作用域并重新加载后，摄像头左上时输入法窗口为 `[0,0][1208,1392]`，右下时为 `[0,0][810,1392]`。等待键盘进程重启、界面布局和动画完成后，已截图确认左上方向中文九宫格全宽、按键比例正常，Telegram 输入框显示正常；先前也验证了右下方向中文和英文键盘的侧边避让。构建和静态检查已通过。独立 Gboard 设置页已确认可见，原有开关状态保留。实际内屏、浮动模式和独立弹窗尚未回归。
