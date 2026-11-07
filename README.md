# 青龙Cookie推送 APK 工程

## 构建方式（二选一）

### 方式 A：GitHub Actions（不用装任何环境，推荐）
1. 在 GitHub 新建一个仓库，把本工程全部文件上传（保持目录结构）
2. 进入仓库 → Actions → 选择 "Build APK" → Run workflow
3. 等几分钟后，在 Actions 运行记录底部的 Artifacts 下载 `ql-cookie-pusher-apk`
4. 把 apk 传到手机安装即可（Android 会提示允许安装未知来源应用）

### 方式 B：Android Studio
1. Android Studio → Open 选择本工程目录
2. 等 Gradle 同步完成 → Build → Build APK
3. 产物在 app/build/outputs/apk/debug/ 下

## 使用
1. 打开 App，填写青龙地址、client_id、client_secret（保存后下次自动填充）
2. 粘贴 JD_COOKIE，可填备注（格式：备注 |UID_xxx，与青龙推送脚本兼容）
3. 点「推送到青龙」：已存在该 pt_pin 则更新，否则新增
