package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SlidingWindowLimit;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("upload")
@SlidingWindowLimit(globalLimit = true)
public class UploadController {

    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    @Resource
    private IBlogService blogService;

    @Value("${cityai.upload-dir:./uploads}")
    private String uploadDir;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        String originalFilename = image == null ? null : image.getOriginalFilename();
        String suffix = StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
        String expectedContentType = IMAGE_CONTENT_TYPES.get(suffix);
        if (image == null || image.isEmpty() || expectedContentType == null
                || !expectedContentType.equalsIgnoreCase(image.getContentType())
                || !hasExpectedImageSignature(image, suffix)) {
            return Result.fail("仅支持 PNG、JPG、JPEG、GIF、WebP 图片，且文件不能为空");
        }
        Long userId = UserHolder.getUser().getId();
        try {
            // 生成新文件名
            String fileName = createNewFileName(originalFilename, userId);
            // 保存文件
            Path destination = imagePath(fileName);
            Files.createDirectories(destination.getParent());
            image.transferTo(destination);
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        if (!org.springframework.util.StringUtils.hasText(filename)) {
            return Result.fail("错误的文件名称");
        }
        Long userId = UserHolder.getUser().getId();
        if (blogService.ownsImage(userId, filename)) {
            return Result.fail("已发布笔记正在使用这张图片，不能删除");
        }
        Path userImageRoot = Path.of(uploadDir).toAbsolutePath().normalize()
                .resolve("blogs").resolve(userId.toString()).normalize();
        Path file;
        try {
            file = imagePath(filename);
        } catch (IllegalArgumentException e) {
            return Result.fail("错误的文件名称");
        }
        if (!file.startsWith(userImageRoot)) {
            return Result.fail("无权删除该图片");
        }
        if (!Files.isRegularFile(file)) {
            return Result.fail("图片不存在");
        }
        try {
            Files.delete(file);
            return Result.ok();
        } catch (IOException e) {
            log.warn("删除未发布图片失败，userId={}, filename={}", userId, filename, e);
            return Result.fail("图片删除失败，请稍后重试");
        }
    }

    private String createNewFileName(String originalFilename, Long userId) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}/{}.{}", userId, d1, d2, name, suffix);
    }

    private Path imagePath(String name) {
        Path root = Path.of(uploadDir).toAbsolutePath().normalize();
        Path path = root.resolve(name.replaceFirst("^/", "")).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("无效的图片路径");
        }
        return path;
    }

    private boolean hasExpectedImageSignature(MultipartFile image, String suffix) {
        try (var input = image.getInputStream()) {
            byte[] header = input.readNBytes(12);
            return switch (suffix) {
                case "png" -> header.length >= 8
                        && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e
                        && header[3] == 0x47 && header[4] == 0x0d && header[5] == 0x0a
                        && header[6] == 0x1a && header[7] == 0x0a;
                case "jpg", "jpeg" -> header.length >= 3
                        && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
                case "gif" -> header.length >= 6
                        && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                        && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
                case "webp" -> header.length >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
                default -> false;
            };
        } catch (IOException e) {
            log.warn("读取上传图片签名失败", e);
            return false;
        }
    }
}
