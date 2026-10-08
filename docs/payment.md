# 微信支付接入边界

小程序包内不能保存商户私钥、API v3 密钥或支付签名逻辑。本项目把支付拆成：

1. `sample-meituan` / `logic.js`：创建订单，调用后端 `/api/orders/{id}/pay`；
2. `server`：持有商户配置，向微信支付 API v3 获取 `prepay_id` 并生成 `wx.requestPayment` 参数；
3. 微信支付异步通知：已实现验签和解密的原型，生产环境需要完善校验和订单状态 CAS 更新。

## 环境变量

| 变量 | 含义 |
| --- | --- |
| `WECHAT_APP_ID` | 小程序 AppID |
| `WECHAT_MCH_ID` | 微信支付商户号 |
| `WECHAT_API_V3_KEY` | API v3 密钥 |
| `WECHAT_MCH_SERIAL_NO` | 商户证书序列号 |
| `WECHAT_MCH_PRIVATE_KEY` | PKCS#8 商户私钥，允许带 PEM 头尾 |
| `WECHAT_OPENID` | 演示用付款用户 OpenID |
| `WECHAT_NOTIFY_URL` | 微信支付异步通知地址 |
| `WECHAT_PLATFORM_PUBLIC_KEY` | 微信支付平台公钥，用于通知验签 |

`WECHAT_OPENID` 和 `WECHAT_NOTIFY_URL` 在这里用于可运行演示；真实业务应从登录态解析 OpenID，
并确保通知地址是 HTTPS 已备案域名。若缺少任一必需变量，服务返回 `mock: true`，明确表示未扣款。

## 真实扣款前置条件

1. 小程序主体完成微信认证并开通微信支付；
2. 后端部署到 HTTPS 域名，配置 `request` / `requestPayment` 合法域名；
3. 设置上述环境变量，不把私钥写入镜像或仓库；
4. 当前 `/api/wechat/notify` 已实现签名校验和 `resource` AES-GCM 解密；生产仍应把内存订单替换为数据库，并加幂等键；
5. 在小程序后台完成支付目录、隐私指引和体验版真机验证。

当前服务的 `MiniAppServer` 已实现 JSAPI 下单参数生成和通知验签/解密，但订单仍是内存存储。
接入生产数据库和幂等更新前，不能把 `/pay` 返回成功直接当作最终支付成功。

## 演示限制

下单金额由服务端商品目录与数量计算，不信任客户端传入的价格；商品目录目前仍是共享演示数据。
当前登录不是 `wx.login` 换取 OpenID 的生产登录流程，也没有用户级订单鉴权。
回调仍须补充时间戳防重放、平台公钥 ID 校验、商户/AppID/金额匹配和持久化幂等处理。
因此配置商户密钥并不等于生产可用。本次验收只覆盖未配置商户时的 mock 链路；真实扣款必须另行真机验收。
