package com.ai.aiworkshop.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;

/**
 * 阶段 5 控制器：多模态（图片理解 + 文生图）。
 *
 * 两大能力：
 *  1) 图片理解（/describe）：把上传图片转成 Spring AI 的 {@link Media}，随 UserMessage 一起发给
 *     通义千问视觉模型 qwen-vl-max（由 DashScope 的 dashScopeChatModel 驱动）。
 *     ⚠️ 为什么不用 DeepSeek？DeepSeek V4 公开 API（api.deepseek.com）仍为 text-only，会把图片忽略，
 *     模型只能回"看不到图片"。视觉理解必须走支持图片输入的模型（qwen-vl-max / qwen-vl-plus 等）。
 *  2) 文生图（/generate）：通义万相（Wanx）。注入 Spring AI 标准的 {@link ImageModel}（DashScope 自动配置
 *     提供的 DashScopeImageModel 实现），call(ImagePrompt) 拿到图片 URL；再代理下载为 base64 data URL 返回，
 *     避免 DashScope 临时 URL 过期导致前端加载失败。
 */
@RestController
@RequestMapping("/api/multimodal")
public class MultimodalController {

    private final ChatClient multimodalClient;
    private final ImageModel imageModel;

    public MultimodalController(@Qualifier("multimodalClient") ChatClient multimodalClient,
                                ImageModel imageModel) {
        this.multimodalClient = multimodalClient;
        this.imageModel = imageModel;
    }

    /**
     * 图片理解：上传一张图片 + 一个问题，模型基于图片作答。
     * 图片以字节数组包成 ByteArrayResource 交给 Media；MIME 类型取自上传文件的 contentType。
     */
    @PostMapping(value = "/describe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> describe(@RequestParam("image") MultipartFile image,
                                        @RequestParam(value = "question", defaultValue = "请描述这张图片的内容") String question) {
        if (image == null || image.isEmpty()) {
            return Map.of("error", "请上传一张图片");
        }
        try {
            byte[] bytes = image.getBytes();
            // 兜底：部分浏览器/场景下 getContentType() 可能为 null，避免 MimeTypeUtils 解析抛异常
            String contentType = image.getContentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = "image/png";
            }
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(contentType))
                    .data(new ByteArrayResource(bytes))
                    .build();
            // Spring AI 1.1.2：user() 不直接吃 UserMessage，需用 Consumer<PromptUserSpec> 在 spec 里 text+media
            String answer = multimodalClient.prompt()
                    .user(u -> u.text(question).media(media))
                    .call()
                    .content();
            return Map.of("answer", answer);
        } catch (Exception e) {
            return Map.of("error", "图片理解失败：" + e.getMessage());
        }
    }

    /**
     * 文生图：通义万相（Wanx）。
     * 模型名 / 尺寸来自 application.yml 的 spring.ai.dashscope.image.options，故这里只传 prompt 文本。
     * 通义万相是异步任务：Spring AI 在后台轮询任务状态，直到出图（retry 配置已放大轮询次数）。
     * 拿到图片 URL 后由后端代理下载为 base64 data URL 返回，前端 <img> 直接显示、不受临时 URL 过期影响。
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return Map.of("error", "prompt 不能为空");
        }
        try {
            ImageResponse resp = imageModel.call(new ImagePrompt(prompt));
            String url = resp.getResult().getOutput().getUrl();
            if (url == null || url.isBlank()) {
                return Map.of("error", "文生图未返回图片地址");
            }
            String dataUrl = downloadAsDataUrl(url);
            return Map.of("image", dataUrl, "url", url);
        } catch (Exception e) {
            return Map.of("error", "文生图失败：" + e.getMessage());
        }
    }

    /** 后端代理下载：把图片 URL 拉成字节，编码为 data URL（含 MIME），前端无需关心临时 URL 有效期 */
    private String downloadAsDataUrl(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = resp.body();
        String mime = resp.headers().firstValue("Content-Type").orElse("image/png");
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
