# Linux 隔离构建验证

2026-11-13，control-01 经 SSH 检查 host_id `32673b7e-35b6-47c0-ba16-b58b6c0e2480`、x86_64，使用 `/var/tmp/ezbookkeeping-candidate-ff4e14b28566` 的独立源码与全新候选 tag，不挂生产数据。以下是工作树快照的探索性构建，不代表最终提交、发布或上线。

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
