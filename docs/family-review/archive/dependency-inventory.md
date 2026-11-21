# 依赖声明清单

基线：82920c62fd774b95eaed7fe78938675b56b73cf2。由实际 go.mod 与 package.json 逐条提取；这里的声明核对不等于第三方源码人工审计。漏洞工具结果见 02-security-audit.md。

## Go 模块

| 包 | 版本 | 类型 | 证据 |
|---|---|---|---|
| github.com/aws/aws-sdk-go-v2 | v1.47.5 | 直接 | go.mod:6 |
| github.com/aws/aws-sdk-go-v2/config | v1.33.13 | 直接 | go.mod:7 |
| github.com/aws/aws-sdk-go-v2/credentials | v1.20.13 | 直接 | go.mod:8 |
| github.com/aws/aws-sdk-go-v2/service/s3 | v1.114.6 | 直接 | go.mod:9 |
| github.com/aws/smithy-go | v1.28.7 | 直接 | go.mod:10 |
| github.com/boombuler/barcode | v1.1.0 | 直接 | go.mod:11 |
| github.com/coreos/go-oidc/v3 | v3.21.0 | 直接 | go.mod:12 |
| github.com/extrame/xls | v0.0.2-0.20200426124601-4a6cf263071b | 直接 | go.mod:13 |
| github.com/gin-contrib/cache | v1.4.7 | 直接 | go.mod:14 |
| github.com/gin-contrib/gzip | v1.2.9 | 直接 | go.mod:15 |
| github.com/gin-gonic/gin | v1.12.0 | 直接 | go.mod:16 |
| github.com/go-co-op/gocron/v2 | v2.23.0 | 直接 | go.mod:17 |
| github.com/go-playground/validator/v10 | v10.30.5 | 直接 | go.mod:18 |
| github.com/go-sql-driver/mysql | v1.10.1 | 直接 | go.mod:19 |
| github.com/golang-jwt/jwt/v5 | v5.3.1 | 直接 | go.mod:20 |
| github.com/invopop/jsonschema | v0.14.0 | 直接 | go.mod:21 |
| github.com/lib/pq | v1.12.3 | 直接 | go.mod:22 |
| github.com/mattn/go-sqlite3 | v1.14.52 | 直接 | go.mod:23 |
| github.com/minio/minio-go/v7 | v7.4.1 | 直接 | go.mod:24 |
| github.com/patrickmn/go-cache | v2.1.0+incompatible | 直接 | go.mod:25 |
| github.com/pquerna/otp | v1.5.0 | 直接 | go.mod:26 |
| github.com/sirupsen/logrus | v1.10.2 | 直接 | go.mod:27 |
| github.com/stretchr/testify | v1.12.1 | 直接 | go.mod:28 |
| github.com/urfave/cli/v3 | v3.14.0 | 直接 | go.mod:29 |
| github.com/wk8/go-ordered-map/v2 | v2.1.8 | 直接 | go.mod:30 |
| github.com/xuri/excelize/v2 | v2.11.0 | 直接 | go.mod:31 |
| golang.org/x/crypto | v0.58.0 | 直接 | go.mod:32 |
| golang.org/x/net | v0.62.0 | 直接 | go.mod:33 |
| golang.org/x/oauth2 | v0.37.0 | 直接 | go.mod:34 |
| golang.org/x/text | v0.43.0 | 直接 | go.mod:35 |
| gopkg.in/ini.v1 | v1.67.3 | 直接 | go.mod:36 |
| gopkg.in/mail.v2 | v2.3.1 | 直接 | go.mod:37 |
| xorm.io/builder | v0.3.13 | 直接 | go.mod:38 |
| xorm.io/xorm | v1.4.1 | 直接 | go.mod:39 |
|  |  | 间接 | go.mod:43 |
|  |  | 间接 | go.mod:44 |
|  |  | 间接 | go.mod:45 |
|  |  | 间接 | go.mod:46 |
|  |  | 间接 | go.mod:47 |
|  |  | 间接 | go.mod:48 |
|  |  | 间接 | go.mod:49 |
|  |  | 间接 | go.mod:50 |
|  |  | 间接 | go.mod:51 |
|  |  | 间接 | go.mod:52 |
|  |  | 间接 | go.mod:53 |
|  |  | 间接 | go.mod:54 |
|  |  | 间接 | go.mod:55 |
|  |  | 间接 | go.mod:56 |
|  |  | 间接 | go.mod:57 |
|  |  | 间接 | go.mod:58 |
|  |  | 间接 | go.mod:59 |
|  |  | 间接 | go.mod:60 |
|  |  | 间接 | go.mod:61 |
|  |  | 间接 | go.mod:62 |
|  |  | 间接 | go.mod:63 |
|  |  | 间接 | go.mod:64 |
|  |  | 间接 | go.mod:65 |
|  |  | 间接 | go.mod:66 |
|  |  | 间接 | go.mod:67 |
|  |  | 间接 | go.mod:68 |
|  |  | 间接 | go.mod:69 |
|  |  | 间接 | go.mod:70 |
|  |  | 间接 | go.mod:71 |
|  |  | 间接 | go.mod:72 |
|  |  | 间接 | go.mod:73 |
|  |  | 间接 | go.mod:74 |
|  |  | 间接 | go.mod:75 |
|  |  | 间接 | go.mod:76 |
|  |  | 间接 | go.mod:77 |
|  |  | 间接 | go.mod:78 |
|  |  | 间接 | go.mod:79 |
|  |  | 间接 | go.mod:80 |
|  |  | 间接 | go.mod:81 |
|  |  | 间接 | go.mod:82 |
|  |  | 间接 | go.mod:83 |
|  |  | 间接 | go.mod:84 |
|  |  | 间接 | go.mod:85 |
|  |  | 间接 | go.mod:86 |
|  |  | 间接 | go.mod:87 |
|  |  | 间接 | go.mod:88 |
|  |  | 间接 | go.mod:89 |
|  |  | 间接 | go.mod:90 |
|  |  | 间接 | go.mod:91 |
|  |  | 间接 | go.mod:92 |
|  |  | 间接 | go.mod:93 |
|  |  | 间接 | go.mod:94 |
|  |  | 间接 | go.mod:95 |
|  |  | 间接 | go.mod:96 |
|  |  | 间接 | go.mod:97 |
|  |  | 间接 | go.mod:98 |
|  |  | 间接 | go.mod:99 |
|  |  | 间接 | go.mod:100 |
|  |  | 间接 | go.mod:101 |
|  |  | 间接 | go.mod:102 |
|  |  | 间接 | go.mod:103 |
|  |  | 间接 | go.mod:104 |
|  |  | 间接 | go.mod:105 |
|  |  | 间接 | go.mod:106 |
|  |  | 间接 | go.mod:107 |
|  |  | 间接 | go.mod:108 |
|  |  | 间接 | go.mod:109 |
|  |  | 间接 | go.mod:110 |
|  |  | 间接 | go.mod:111 |
|  |  | 间接 | go.mod:112 |
|  |  | 间接 | go.mod:113 |
|  |  | 间接 | go.mod:114 |
|  |  | 间接 | go.mod:115 |
|  |  | 间接 | go.mod:116 |
|  |  | 间接 | go.mod:117 |

## 前端直接声明

| 包 | 版本 | 类型 |
|---|---|---|
| @mdi/js | 7.4.47 | dependencies |
| @vuepic/vue-datepicker | 14.0.0 | dependencies |
| axios | 1.21.0 | dependencies |
| cbor-js | 0.1.0 | dependencies |
| chardet | 2.2.0 | dependencies |
| clipboard | 2.0.11 | dependencies |
| crypto-js | 4.2.0 | dependencies |
| decimal.js | 10.6.0 | dependencies |
| dom7 | 4.0.6 | dependencies |
| echarts | 6.1.0 | dependencies |
| framework7 | 9.3.0 | dependencies |
| framework7-icons | 5.0.5 | dependencies |
| framework7-vue | 9.3.0 | dependencies |
| jalaali-js | 2.0.1 | dependencies |
| leaflet | 1.9.4 | dependencies |
| line-awesome | 1.3.0 | dependencies |
| moment | 2.31.0 | dependencies |
| moment-timezone | 0.6.6 | dependencies |
| monaco-editor | 0.57.0 | dependencies |
| pinia | 4.1.0 | dependencies |
| register-service-worker | 1.7.2 | dependencies |
| skeleton-elements | 4.0.1 | dependencies |
| swiper | 12.2.0 | dependencies |
| ua-parser-js | 1.0.41 | dependencies |
| vue | 3.5.44 | dependencies |
| vue-echarts | 8.3.2 | dependencies |
| vue-i18n | 11.4.17 | dependencies |
| vue-router | 5.4.0 | dependencies |
| vuedraggable | 4.1.0 | dependencies |
| vuetify | 4.3.1 | dependencies |
| @jest/globals | 30.5.2 | devDependencies |
| @tsconfig/node26 | 26.0.1 | devDependencies |
| @types/cbor-js | 0.1.1 | devDependencies |
| @types/crypto-js | 4.2.2 | devDependencies |
| @types/git-rev-sync | 2.0.2 | devDependencies |
| @types/node | 26.6.6 | devDependencies |
| @types/ua-parser-js | 0.7.39 | devDependencies |
| @vitejs/plugin-vue | 6.0.10 | devDependencies |
| @vue/eslint-config-typescript | 14.9.0 | devDependencies |
| @vue/tsconfig | 0.9.1 | devDependencies |
| cross-env | 10.1.0 | devDependencies |
| eslint | 10.14.0 | devDependencies |
| eslint-plugin-vue | 10.12.0 | devDependencies |
| git-rev-sync | 3.0.2 | devDependencies |
| postcss-preset-env | 11.6.2 | devDependencies |
| sass | 1.107.1 | devDependencies |
| terser | 5.51.2 | devDependencies |
| ts-node | 10.9.2 | devDependencies |
| typescript | 6.0.3 | devDependencies |
| vite | 8.3.5 | devDependencies |
| vite-plugin-checker | 0.14.5 | devDependencies |
| vite-plugin-pwa | 2.0.0 | devDependencies |
| vite-plugin-vuetify | 2.1.3 | devDependencies |
| vitest | 5.0.5 | devDependencies |
| vue-tsc | 3.3.14 | devDependencies |

## 来源与覆盖限制

- Go 直接声明可归为 AWS SDK、Gin、Xorm/驱动、OAuth/OIDC/JWT/OTP、文件解析、调度和通用工具；没有 replace 声明，没有 boltdb-go/bolt 条目。
- 包名用途归类不是注册者身份核验；没有逐包证明发布者未被接管，没有逐条 go.sum 哈希对照上游制品。
- extrame/xls、extrame/ole2、robfig/go-cache 等旧模块及伪版本优先补人工源码和替代性评估；并非已认定恶意包。
- 前端传递依赖及 integrity/resolved URL 以 package-lock.json 为准；没有在此重复千行锁文件，也没有逐包人工审计。
- go.sum 和 package-lock.json 已在当前仓库存在，工具完整性检查受限与漏洞告警详见安全报告。
