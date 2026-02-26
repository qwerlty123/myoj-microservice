# MinIO 用户头像上传 - 一步步使用说明

## 一、MinIO 是什么

MinIO 是一个对象存储服务，可以理解成「自己搭的网盘」，用来存用户上传的头像等文件。你的项目里已经接好了上传接口，只要把 MinIO 跑起来并改好配置就能用。

---

## 二、安装并启动 MinIO

### 方式 A：用 Docker（推荐）

1. **安装 Docker**  
   若未安装：到 [Docker 官网](https://www.docker.com/products/docker-desktop/) 下载并安装 Docker Desktop。

2. **拉取并运行 MinIO 容器**（在终端执行）：

   ```bash
   docker run -d -p 9000:9000 -p 9001:9001 --name minio-myoj ^
     -e "MINIO_ROOT_USER=minioadmin" ^
     -e "MINIO_ROOT_PASSWORD=minioadmin123" ^
     minio/minio server /data --console-address ":9001"
   ```

   - 若在 **Linux / Mac** 下，把 `^` 换成 `\`，并写成一行或按行用 `\` 换行。
   - `9000`：API 端口（程序上传用）  
   - `9001`：控制台端口（浏览器管理用）  
   - `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`：控制台和 API 的账号密码，可改成你自己的。

3. **确认 MinIO 已启动**  
   浏览器打开：`http://localhost:9001`  
   用上面的账号密码登录，能进控制台即表示成功。

### 方式 B：直接下载 MinIO 二进制（不用 Docker）

1. 打开：<https://min.io/download>  
   选择对应系统（如 Windows 64 位）下载 `minio.exe`。

2. 在命令行进入 `minio.exe` 所在目录，执行：

   ```bash
   minio.exe server D:\minio-data --console-address ":9001"
   ```

   - `D:\minio-data` 可改成你希望存文件的目录。  
   - 启动后终端会提示：API 地址（一般是 `http://localhost:9000`）和控制台地址（一般是 `http://localhost:9001`）。

3. 浏览器打开控制台地址，用终端里显示的默认账号密码登录（首次运行会给出）。

---

## 三、在 MinIO 里创建存储桶（Bucket）

1. 打开 MinIO 控制台：`http://localhost:9001`，登录。
2. 左侧点 **Buckets** → **Create Bucket**。
3. **Bucket Name** 填：`myoj`（和下面配置里的 `bucket` 一致）。
4. 点 **Create** 完成。

桶名必须和你在 yml 里配置的 `file.minio.bucket` 一致，否则上传会报错。

---

## 四、在项目里配置 MinIO

打开用户服务的配置文件：

`myoj-backend-microservice/myoj-backend-user-service/src/main/resources/application.yml`

找到或添加：

```yaml
file:
  minio:
    accessKey: minioadmin
    secretKey: minioadmin123
    endpoint: http://localhost:9000
    bucket: myoj
```

按你的实际环境修改：

| 配置项     | 说明 |
|------------|------|
| `accessKey` | MinIO 登录用户名（Docker 示例里是 `minioadmin`） |
| `secretKey` | MinIO 登录密码（Docker 示例里是 `minioadmin123`） |
| `endpoint` | MinIO API 地址，本机即 `http://localhost:9000`；远程则改为 `http://服务器IP:9000` |
| `bucket`   | 存储桶名称，要和上面创建的桶名一致，如 `myoj` |

若用 `application-dev.yml` 等环境区分，可在对应环境里覆盖上述四项。

---

## 五、启动你的用户服务

1. 确保 MinIO 已启动（控制台能访问）。
2. 确保 MySQL、Nacos 等依赖已启动（若你项目里有）。
3. 启动 **myoj-backend-user-service**（IDEA 里运行主类或 `mvn spring-boot:run`）。

---

## 六、如何调用上传头像接口

你的接口是：**POST** `/api/user/upload/avatar`

### 请求方式

- **Content-Type**：`multipart/form-data`
- **参数**：
  - `file`：图片文件（必填）
  - `userId`：用户 id（必填）

### 示例 1：用 Postman / Apifox

1. 选择 **POST**，URL 填：`http://localhost:8102/api/user/upload/avatar`（端口以你实际为准）。
2. **Body** 选 **form-data**，添加：
   - key：`file`，类型选 **File**，选择一张图片；
   - key：`userId`，类型 **Text**，填当前用户 id（如 `1`）。
3. 若需要登录态，在 **Headers** 里加：`Authorization: Bearer 你的JWT`。
4. 发送请求，返回里会带新头像的 URL。

### 示例 2：用 curl

```bash
curl -X POST "http://localhost:8102/api/user/upload/avatar" ^
  -F "file=@C:\path\to\avatar.jpg" ^
  -F "userId=1"
```

（Linux/Mac 下把 `^` 换成 `\`。）

### 示例 3：前端用 FormData

```javascript
const formData = new FormData();
formData.append('file', fileInput.files[0]);  // input[type=file] 选中的文件
formData.append('userId', userId);

fetch('http://localhost:8102/api/user/upload/avatar', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer ' + token   // 如需登录
  },
  body: formData
})
  .then(res => res.json())
  .then(data => {
    console.log('新头像地址:', data.data);  // 即 MinIO 返回的 URL
  });
```

---

## 七、返回结果说明

- 成功时，接口返回里的 `data` 即为头像的完整 URL，例如：  
  `http://localhost:9000/myoj/avatar/1_1730123456789.jpg`
- 前端拿到后，可把该 URL 存到用户信息里或直接用于 `<img src="...">` 显示头像。
- 再次上传会覆盖该用户旧头像（代码里会删 MinIO 上的旧文件）。

---

## 八、常见问题

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 连接被拒绝 / Connection refused | MinIO 未启动或端口不对 | 确认 MinIO 在 9000 端口，且 `endpoint` 写对 |
| Access Denied / 403 | accessKey/secretKey 错误或桶策略限制 | 检查 yml 里的账号密码；在 MinIO 控制台检查桶的 Access 策略 |
| Bucket 不存在 | 未创建桶或 bucket 名不一致 | 在 MinIO 控制台创建名为 `myoj` 的桶，并保证 yml 里 `bucket: myoj` |
| 只能上传图片 | 接口做了校验 | 仅允许 `image/*`，且限制约 2MB，属正常 |
| 前端跨域报错 | 浏览器跨域限制 | MinIO 控制台 → Bucket → Anonymous 可设读权限；或通过网关/Nginx 代理 MinIO |

---

## 九、简要流程回顾

1. 安装并启动 MinIO（Docker 或二进制）。  
2. 浏览器打开控制台，创建名为 `myoj` 的 Bucket。  
3. 在用户服务 `application.yml` 里配置 `file.minio`（accessKey、secretKey、endpoint、bucket）。  
4. 启动 myoj-backend-user-service。  
5. 用 Postman/前端按 `multipart/form-data` 调用 `POST /api/user/upload/avatar`，传 `file` 和 `userId`。  
6. 使用返回的 URL 作为用户头像地址。

按以上步骤做完，MinIO 用户头像上传即可正常使用。
