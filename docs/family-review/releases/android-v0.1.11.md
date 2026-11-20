# android-v0.1.11

- 产品 `ezbookkeeping-family` 的 Android 原生客户端
- 包名 `top.fjzhang233.books`
- versionCode `13`
- 上一正式版本 `android-v0.1.10`

## 更新内容

- 使用 Navigation 3 统一页面返回栈，修复 Android 16 上从月报表边缘滑动返回时画面滞留的问题。
- 从账单详情的分类可进入独立分类统计。个人报表支持分类环比、二级分类饼图和分类流水抽屉，报表流水与主页共用流水行样式。
- 个人与家庭报表统一分类配色，同一分类的饼图扇区、图标和占比条使用对应的收支色板。分类排名把名称和占比分组，金额靠右对齐。
- 报表、记账、日历和搜索等自定义控件的按压反馈贴合可见轮廓，并支持大字号、键盘焦点和 TalkBack。主题选择使用底部抽屉。

## 验证范围

- Android 单测、Lint 和 Debug 构建，以及 Android 16 上的返回手势、记账反馈、账单详情、主题选择和分类报表回归。
- 家庭月报和年报的支出与收入色板，以及浅色、深色主题下扇区和分类排名的像素核对。
- GitHub Actions 对正式 APK 执行签名、包名、版本和摘要校验，并发布 `ezbookkeeping-android-0.1.11.apk`、`update.json`、`SHA256SUMS`。
