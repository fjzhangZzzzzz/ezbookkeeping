# Linux 隔离构建验证

2026-11-13，control-01 经 SSH 检查 host_id `32673b7e-35b6-47c0-ba16-b58b6c0e2480`、x86_64，使用 `/var/tmp/ezbookkeeping-candidate-ff4e14b28566` 的独立源码与全新候选 tag，不挂生产数据。本文件保留早期探索、资源失败和最终组合候选的来源。当前候选来自完整冻结提交 `462ff2b3f66659daa92ca2d19255924d1e2a3b70`，Linux 后端与 Windows 静态工件组合的实际验收见末节；早期探索不代表最终提交、发布或上线。

## 官方基础镜像证据

Windows 通过既有代理运行 `crane v0.20.7` 的 `digest --platform linux/amd64` 和 `config --platform linux/amd64`；后者的原始 JSON SHA256 与 `docker load` 后的 image ID 一致。以下 manifest digest 是平台清单摘要，config digest 是镜像 ID，两者不是同一个摘要。

| 官方标签 | linux/amd64 manifest SHA256 | config SHA256 / 本机 image ID |
|---|---|---|
| golang:1.27.4-alpine3.24 | ea30e25680a5470bac02b08bb0e0a735fb75bd0ab7d431b9b95ee0abef362586 | 3f9f7b68d2300deb4acba48650f28dec9098ead84cd167a56c0e2bf361f24592 |
| node:26.13.0-alpine3.24 | b2646ba6d774dc22c24d89fc37103fcf4c4467e870c78b9519aa403300b2835a | 02a728b94c55ca63ed829ab3194fab42ea4633551821a62f230a1818577c435e |
| alpine:3.24.3 | c26612752c78b18faacd63c7d50f4592c2e7b0f25e0e20e82bd74e465c57ab0d | b92841763c5c2140a20eeb2a44e94ebb0e80fd51d9d6b5895ea72fa999525d68 |

`crane pull --platform linux/amd64` 下载官方镜像，传入控制节点 `docker load`。离线导入不保留 RepoDigest，因此临时 Dockerfile 对照归档 Dockerfile **只替换三条 FROM 中的官方 tag@manifest digest 为表中对应的本机 config/image ID**，其余行逐行一致。正式 Dockerfile 保留已验证的官方 manifest 固定值。所用源码归档包含 Git tracked 与未忽略新文件，按根路径排除 `.git`、`.work`、`.cache`、`.tools`、`node_modules`、运行数据和机密等，并按 Git index 恢复执行位。不能把本机 image ID 称为 registry manifest digest。

探索归档 1183 文件，tar SHA256 `9a7bc4943766c997e72cf7e90d1863d3d65520ddc3465190df6ecb50c107089c`；逐文件内容 SHA256 清单的 SHA256 `88d5adaf57454ce41882b602436b32edc08b239bb48d62ae58e487d9dd85bd69`。远端逐文件核验通过，核验 `pkg/auth/oauth2/data`、`pkg/log`、`pkg/storage` 和 `build.sh` 执行位。首次归档误按任意路径段排除 data/log/storage，后端 lint 因缺源码失败；已纠正，失败结果不作为有效验收。

重放构建时将 `src-v3/Dockerfile` 仅做上表三处替换生成 `Dockerfile.offline-v3`，置于源码目录外，保证 `diff -u` 只有 FROM 三行。通过临时回环 SSH 反向转发将已有本机代理提供到构建容器，不变更主机 daemon 或系统代理。核心命令：

```sh
cd /var/tmp/ezbookkeeping-candidate-ff4e14b28566/src-v3
sudo -n env DOCKER_BUILDKIT=0 docker build --network host \
  --cpu-period 100000 --cpu-quota 200000 \
  --build-arg HTTP_PROXY=http://127.0.0.1:17897 \
  --build-arg HTTPS_PROXY=http://127.0.0.1:17897 \
  --build-arg BUILD_PIPELINE=1 --build-arg CHECK_3RD_API=0 \
  --build-arg BUILD_NODE_OPTIONS=--max-old-space-size=1536 \
  --build-arg BUILD_COMMIT=snapshot-9a7bc4943766 \
  -t ezbookkeeping-isolated:9a7bc4943766 -f - . < ../Dockerfile.offline-v3
```

`CHECK_3RD_API=0` 使用上游现有逻辑跳过外部 API 检查，保留本地测试；未禁用 lint 或前端类型检查。构建结果、隔离启动、SQLite、UID、工件归档和 SHA 尚待确认，未经过验证的能力不能作通过陈述。最终稳定源码须重新归档和构建，并保留该次准确的来源、依赖与镜像证据。

## 冻结提交的正式候选构建

冻结应用提交 `ea1852fea60776c514a4d5d83a9b2d2cb8419228`，用 `git archive --format=tar` 生成源码：tar SHA256 `082ea1456212e682fa1c97f8b6d5578578602e572f896af41267fe413abe8167`；1184 个文件逐项内容清单 SHA256 `63d5b3a94b83f156535bc6bb9da80f1abbbfe82907a1f94ac2bc192e5cf72ec5`。远端校验通过，Git 归档保留执行权限；根级运行数据、工具、缓存和 `.git` 不在归档。构建上下文使用提交内 `.dockerignore`。冻结提交 Dockerfile 与临时离线 Dockerfile 的差异仍仅限前述三条已校验 FROM。

使用上面命令将构建路径换成 `final-ea1852`、Dockerfile 换成 `../Dockerfile.offline-final`、镜像标签换成 `ezbookkeeping-isolated:ea1852fea607`，并将 `BUILD_COMMIT` 设为完整提交 SHA。首次构建在 Alpine 官方索引 TLS 临时失败；经回环代理复核 APKINDEX HTTP 200 后一次有界重试已通过 apk 安装。这是当时的进行中记录；随后被新冻结提交取代，不能替代最终候选验收。

## 最新冻结提交

上述 `ea1852...` 随许可证信息修订被 `462ff2b3f66659daa92ca2d19255924d1e2a3b70` 取代，其构建作为探索中止，不代表产品构建完成。最新提交的标准 Git 归档 SHA256 `6e98cae257c75d31cab353ba8e2583375cfc6a1b2ae077da752b099cd5d81f1b`，1184 文件内容清单 SHA256 `005f683bcf4c025f70701219f794249273d3f6119dbac8a8e84c778bac93c991`；远端逐文件及执行位核验通过。源 Dockerfile 与前次冻结提交完全相同，因此沿用仅替换三条 FROM 的临时离线文件，`BUILD_COMMIT=462ff2b3f66659daa92ca2d19255924d1e2a3b70`，标签 `ezbookkeeping-isolated:462ff2b3f666`。这是当时后端构建中的记录；最终结果见以下阶段与组合验收。

## 最终候选的实际离线阶段与资源结果

最终源码 462ff2 的首次 legacy 构建：后端 `go vet -v ./...`、`go test ./... -v` 和二进制构建均通过，后端层 `sha256:45935cd7e1682994e49f9bad6b3d8d4068b6ba623bb80215edb1a93b42289c11` 中二进制 SHA256 `eb2f475daa6f03d84dae7f2f2c8e506d438fa721c7e06f480e1bd2a5267c309c`，`--version` 显示完整 462ff2 提交。构建使用 `BUILD_PIPELINE=1`、`CHECK_3RD_API=0`、无 `SKIP_TESTS`。legacy 构建器未自动设置 `BUILDPLATFORM`，首次到前端 FROM 报空平台；后续临时 Dockerfile 以同提交已验后端 image ID 代替整个后端阶段，官方 Node/Alpine 的已验证 config ID 代替原 tag@manifest digest，前端 `--platform` 固定为 `linux/amd64`；其余前端和 runtime 指令保持原样。此路径不等于无修改地运行原 Dockerfile，最终制品须如实说明。

前端首次 Node 堆 1536 MiB 在 `vue-tsc` 达到 V8 堆上限，退出1，容器 `OOMKilled=false`。确认宿主内存可用约6.24 GiB 后，使用 Node heap 2560 MiB、构建容器4 GiB、两核重试：原 `vue-tsc --noEmit && eslint . --fix` 通过，Vitest 17/17 文件、38689/38689 测试通过；原 Vite 打包阶段容器 OOMKilled=true、退出1。保留其临时前端快照 image `sha256:ee6c7ea90dbb5864db6258101853e3e2ae2484459487272b32a222c76d977c15`，复核源文件。1184 个归档文件中18个 docs/deploy 文件依 `.dockerignore` 不入构建上下文，归档本身均已校验；其余1166项中1165项哈希一致，唯一 `.dockerignore` 被 legacy Docker 为临时 Dockerfile 追加空行和 `.dockerfile.89118019852ca71907bf` 忽略项，原有内容逐字节一致；源码、lock、vite 配置全部一致。安装版本 Vite 8.3.5、Terser 5.51.2、DOMPurify 3.4.17 均与 lock 一致。

随后在前端快照内使用官方 Vite API `build({build:{terserOptions:{maxWorkers:1}}})`，`NODE_ENV=production`，Node heap 2560 MiB、cpuset 0,1、CPU 2、内存/交换上限4 GiB，清空隔离容器内 partial dist 后重新打包。`resolveConfig` 实测仍启用 terser、patched-monaco-sanitizer、checker、PWA；官方 Node 容器实测 `cpus().length=8`、`availableParallelism()=2`，已核 Vite Terser worker 池使用后者并显式上限1。自定义 worker minifier 的 Promise.all 不受该参数覆盖。该打包仍因构建容器 OOMKilled=true、exit137 而失败；不是源码功能测试失败，未产生可验收 Linux 前端产物。后续静态资源改由冻结源码的 Windows 构建产物组合到已验证 Linux 后端，须另记准确来源和验收。

## 组合镜像与初步隔离验收

Windows 端从同一标准 Git 归档生成生产静态资源，Node 26.12.1、npm 11.20.0；`npm ci`、`npm run lint`、`npm run build` 均通过，lint 后1184项源码 SHA 未变，lock SHA256 `c6d7d545a4f98afdc857d0373843e35fa3ca260ac0a049f395114549e90ae17b`，构建注入完整提交。dist tar SHA256 `48f14690df948d5b7466a4a38e2c3e3dd86b00255c5918dd24080ff345bd4ba5`，109 文件清单 SHA256 `691ad9a3349a9c9f87978b386dafc827ebf593bce183cea39348922260a00b8d`，远端安全路径和精确集合、逐项 SHA 均通过；Windows 工件证明记载 DOMPurify 3.4.17、无 3.4.15、八项更新的许可证 URL。

临时组合 Dockerfile 将已验证的 462 后端层作为 `be-builder`，以 scratch 前端阶段只复制上述经验证 dist，runtime 保留冻结 Dockerfile 指令且 Alpine 换成已验证本地 config ID。该具体混合构建成功，`linux/amd64` 本地 image ID `sha256:e7601cbf24091f538cbe147f4664ed02a5fb1b5c6a6315fe3534a6a1f07148f8`，默认用户 `1000:1000`；`docker save` 归档 SHA256 `b7281709f90fc6a4c00ef9b29c2aec919c02d4fe474a2c4f21c9b4fc51da27eb`，原始构建副本保存在 control 的 `/var/tmp/ezbookkeeping-candidate-ff4e14b28566` 隔离目录。随后精确镜像已另存 root-only 的 `/srv/homelab/recovery/platform-images/<image-ID-hex>.tar.age` 和索引，源码、Git bundle、前端 tar/manifest/proof 已进入 `/srv/homelab/recovery/ezbookkeeping/source/` 并核验摘要；尚未进入真实 Backrest 快照，不将持久恢复工件称为生产备份恢复完成。镜像内109个 public 文件与 dist 清单逐项相符，后端二进制 SHA 与已验证层一致。image ID、docker-save 归档 SHA 和 registry manifest digest 不可互称；本候选未推送 registry。

同镜像以 `--network none`、UID/GID1000、只读根文件系统、独立 SQLite 和存储目录运行：health 返回完整462提交；合成账号通过 CLI 创建并完成密码登录，SQLite 账户创建/读取、家庭 overview HTTP 成功；根目录写入被拒，storage 合成文件读写成功；重启后账户、家庭接口与 storage 文件仍在，SQLite `integrity_check=ok`。未挂载生产数据、凭据或公网网络。

隔离配置来自候选 INI：初次启动依次暴露缺失的 uuid generator、duplicate checker、禁地图时仍必填的 Amap 验证方式、exchange rates data source；填入本地合成配置后才可启动。`--network none` 无非回环地址，测试专用 `http_addr=127.0.0.1` 以供 request ID 初始化；OIDC 暂禁用以避免接触生产身份。故该轮不验证真实 OIDC、不代表原精简模板可用。正式候选模板需补齐所需键及 data import/export 功能开关，并以同镜像重新验证，真实 Pocket ID、HTTPS、生产备份恢复和真实设备验收均未完成。

修正后的候选 INI 模板随后以同一镜像重新渲染并启动，测试差异仅为 `http_addr=127.0.0.1`、本地 root_url、精确 loopback CIDR、合成密钥和为避免连接生产身份而暂禁 OIDC；其 data import/export 开关均开启。真实 HTTP 创建合成分类与交易，个人 CSV 导出并经 `parse_import` 解析出一行、家庭 CSV 导出均通过。真实 Chrome 经 Windows 和 control 双重回环转发访问该隔离实例，桌面家庭页、CSV 下载及 390×844 手机家庭入口通过，控制台无错误；浏览器结果由独立验收人员提供。公网 TLS、真实 Pocket ID 和 Backrest 真实备份恢复未测。

同修正模板和同镜像又使用合成、未注册的 client ID/secret，在无端口映射的临时专属 Docker bridge 上启动，真实应用 Go OIDC 初始化成功，TLS 与 issuer discovery 正常；直接读取应用 OAuth 302 且不跟随跳转，断言 HTTPS issuer 域名、精确 `https://books.fjzhang233.top/oauth2/callback`、S256、非空 state/challenge 和合成 client ID。该测试没有读取真实客户端秘密、创建客户端、完成身份登录或绑定，临时 OIDC 容器及专属网络已移除。个人 CSV 的解析结果已调用 `transactions/import.json` 真正导入1笔，再读家庭明细确认原始和导入交易均持久存在。

独立浏览器验收在上述隔离实例完成移动真实 UI 闭环：Home 打开新增，选择普通支出并输入1.23元，保存回 Home 后经正常家庭入口查看；支出从24.68元变25.91元，余额为-25.91元，`linux-ui-save-1.23` 新明细出现，未重载或用 API 代写，控制台无错误及警告。完成后移除本次运行容器和18190回环转发，保留合成数据与候选工件；构建代理隧道亦已关闭。此结果不代表真实手机主屏幕安装场景通过。
