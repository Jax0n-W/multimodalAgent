package com.multimodalAgent.agent.service.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.EmotionLabel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
/**
 * MediaPipe 视觉情绪分析适配器。
 *
 * <p>http 模式可对接真正的 MediaPipe Face Mesh 服务；local-image 模式会读取图片像素，
 * 按文档中的眉部、眼部、嘴部和肌肉紧绷特征计算视觉情绪分数。</p>
 */
public class MediaPipeClient {

    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final multimodalAgentProperties properties;
    private final WebClient.Builder webClientBuilder;

    public MediaPipeClient(multimodalAgentProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<MultimodalSignal> analyze(FilePart media) {
        if (media == null) {
            return Mono.empty();
        }
        multimodalAgentProperties.MediaPipe mediaPipe = properties.getMultimodal().getMediaPipe();
        if ("http".equalsIgnoreCase(mediaPipe.getMode())) {
            return analyzeByHttp(media, mediaPipe.getUrl())
                    .onErrorResume(ignored -> analyzeLocally(media));
        }
        return analyzeLocally(media);
    }

    private Mono<MultimodalSignal> analyzeByHttp(FilePart media, String url) {
        return DataBufferUtils.join(media.content(), MAX_IMAGE_BYTES)
                .map(this::toBytes)
                .flatMap(bytes -> analyzeBytesByHttp(bytes, media, url)
                        .onErrorResume(ignored -> Mono.just(analyzeImageBytes(bytes, media))));
    }

    private Mono<MultimodalSignal> analyzeBytesByHttp(byte[] bytes, FilePart media, String url) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return media.filename();
                    }
                })
                .contentType(media.headers().getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : media.headers().getContentType());
        return webClientBuilder.build()
                .post()
                .uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::fromMediaPipeJson);
    }

    private Mono<MultimodalSignal> analyzeLocally(FilePart media) {
        return DataBufferUtils.join(media.content(), MAX_IMAGE_BYTES)
                .map(this::toBytes)
                .map(bytes -> analyzeImageBytes(bytes, media))
                .onErrorResume(ignored -> Mono.just(unsupported(media)));
    }

    private byte[] toBytes(DataBuffer buffer) {
        try {
            ByteBuffer byteBuffer = buffer.asByteBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private MultimodalSignal analyzeImageBytes(byte[] bytes, FilePart media) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return unsupported(media);
            }
            VisionFeatures features = extractFeatures(image);
            double browScore = features.browShadow() > 0.34 ? 1.5 : features.browShadow() > 0.26 ? 0.8 : 0.0;
            double eyeScore = features.eyeDarkness() > 0.42 ? 1.0 : features.eyeDarkness() > 0.32 ? 0.5 : 0.0;
            double mouthScore = features.mouthDown() > 0.16 ? 1.0 : features.mouthDown() > 0.08 ? 0.5 : 0.0;
            double tensionScore = features.muscleTension() > 0.38 ? 1.5 : features.muscleTension() > 0.28 ? 0.8 : 0.0;
            double lowEnergyScore = features.faceBrightness() < 0.36 && features.saturation() < 0.32 ? 0.8 : 0.0;
            double score = clamp(browScore + eyeScore + mouthScore + tensionScore + lowEnergyScore, 0.0, 4.5);
            EmotionLabel emotion = emotionFromVisualScore(score);
            double confidence = clamp(0.56 + features.faceCoverage() * 0.28 + features.symmetry() * 0.12, 0.55, 0.9);
            String evidence = String.format(Locale.ROOT,
                    "MediaPipe 本地视觉分析：faceDetected=%s, faceCoverage=%.2f, browShadow=%.2f, eyeDarkness=%.2f, mouthDown=%.2f, muscleTension=%.2f, brightness=%.2f, saturation=%.2f, visualScore=%.2f。",
                    features.faceDetected(),
                    features.faceCoverage(),
                    features.browShadow(),
                    features.eyeDarkness(),
                    features.mouthDown(),
                    features.muscleTension(),
                    features.faceBrightness(),
                    features.saturation(),
                    score);
            return new MultimodalSignal("visual", emotion, score, confidence, evidence);
        } catch (Exception ignored) {
            return unsupported(media);
        }
    }

    private VisionFeatures extractFeatures(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Rect face = detectFaceRegion(image);
        RegionStats faceStats = stats(image, face);
        RegionStats upper = stats(image, face.slice(0.18, 0.45, 0.08, 0.92));
        RegionStats brow = stats(image, face.slice(0.20, 0.34, 0.16, 0.84));
        RegionStats eyes = stats(image, face.slice(0.32, 0.50, 0.10, 0.90));
        RegionStats mouth = stats(image, face.slice(0.62, 0.84, 0.22, 0.78));
        RegionStats mouthCenter = stats(image, face.slice(0.64, 0.82, 0.38, 0.62));
        RegionStats mouthCorners = stats(image, face.slice(0.64, 0.84, 0.18, 0.38))
                .merge(stats(image, face.slice(0.64, 0.84, 0.62, 0.82)));

        double browShadow = clamp((faceStats.brightness() - brow.brightness()) + brow.darkRatio() * 0.45, 0.0, 1.0);
        double eyeDarkness = clamp(eyes.darkRatio() * 0.75 + (upper.contrast() - faceStats.contrast()) * 0.4, 0.0, 1.0);
        double mouthDown = clamp((mouthCorners.darkRatio() - mouthCenter.darkRatio()) * 0.75
                + (mouthCenter.brightness() - mouthCorners.brightness()) * 0.45, 0.0, 1.0);
        double muscleTension = clamp(faceStats.contrast() * 0.7 + Math.abs(leftBrightness(image, face) - rightBrightness(image, face)) * 0.7, 0.0, 1.0);
        return new VisionFeatures(
                face.area() < width * height * 0.92,
                (double) face.area() / Math.max(1, width * height),
                clamp(1.0 - Math.abs(leftBrightness(image, face) - rightBrightness(image, face)) * 2.0, 0.0, 1.0),
                faceStats.brightness(),
                faceStats.saturation(),
                browShadow,
                eyeDarkness,
                mouthDown,
                muscleTension);
    }

    private Rect detectFaceRegion(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        int skinPixels = 0;
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 240);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                if (looksLikeSkin(rgb)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    skinPixels++;
                }
            }
        }
        int sampled = Math.max(1, (image.getWidth() / step) * (image.getHeight() / step));
        if (skinPixels < sampled * 0.015 || maxX <= minX || maxY <= minY) {
            int x = (int) (image.getWidth() * 0.20);
            int y = (int) (image.getHeight() * 0.12);
            int w = (int) (image.getWidth() * 0.60);
            int h = (int) (image.getHeight() * 0.72);
            return new Rect(x, y, w, h).bounded(image.getWidth(), image.getHeight());
        }
        int padX = (maxX - minX) / 5;
        int padY = (maxY - minY) / 4;
        return new Rect(minX - padX, minY - padY, (maxX - minX) + padX * 2, (maxY - minY) + padY * 2)
                .bounded(image.getWidth(), image.getHeight());
    }

    private boolean looksLikeSkin(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return r > 70 && g > 45 && b > 35 && r > g && g >= b * 0.72 && max - min > 12;
    }

    private RegionStats stats(BufferedImage image, Rect rect) {
        Rect bounded = rect.bounded(image.getWidth(), image.getHeight());
        int step = Math.max(1, Math.min(bounded.width(), bounded.height()) / 80);
        double brightness = 0.0;
        double saturation = 0.0;
        double squared = 0.0;
        int dark = 0;
        int count = 0;
        for (int y = bounded.y(); y < bounded.y() + bounded.height(); y += step) {
            for (int x = bounded.x(); x < bounded.x() + bounded.width(); x += step) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                double value = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
                double sat = saturation(r, g, b);
                brightness += value;
                saturation += sat;
                squared += value * value;
                if (value < 0.30) {
                    dark++;
                }
                count++;
            }
        }
        if (count == 0) {
            return new RegionStats(0.5, 0.0, 0.0, 0.0);
        }
        double mean = brightness / count;
        double variance = Math.max(0.0, squared / count - mean * mean);
        return new RegionStats(mean, saturation / count, Math.sqrt(variance), (double) dark / count);
    }

    private double saturation(int r, int g, int b) {
        double max = Math.max(r, Math.max(g, b)) / 255.0;
        double min = Math.min(r, Math.min(g, b)) / 255.0;
        return max == 0.0 ? 0.0 : (max - min) / max;
    }

    private double leftBrightness(BufferedImage image, Rect face) {
        return stats(image, face.slice(0.0, 1.0, 0.0, 0.5)).brightness();
    }

    private double rightBrightness(BufferedImage image, Rect face) {
        return stats(image, face.slice(0.0, 1.0, 0.5, 1.0)).brightness();
    }

    private EmotionLabel emotionFromVisualScore(double score) {
        if (score >= 4.0) {
            return EmotionLabel.HIGH_RISK;
        }
        if (score >= 3.0) {
            return EmotionLabel.DEPRESSED;
        }
        if (score >= 2.0) {
            return EmotionLabel.ANXIETY;
        }
        return EmotionLabel.NORMAL;
    }

    private MultimodalSignal fromMediaPipeJson(JsonNode node) {
        String rawEmotion = node.path("emotion").asText(node.path("visualEmotion").asText("NORMAL"));
        EmotionLabel emotion = parseEmotion(rawEmotion);
        double score = node.path("score").asDouble(node.path("visualScore").asDouble(scoreForEmotion(emotion)));
        double confidence = node.path("confidence").asDouble(0.82);
        String evidence = node.path("evidence").asText("MediaPipe Face Mesh 服务返回视觉情绪结果。");
        if (node.has("features")) {
            evidence = evidence + " features=" + node.path("features");
        }
        return new MultimodalSignal("visual", emotion, score, confidence, evidence);
    }

    private EmotionLabel parseEmotion(String emotion) {
        String normalized = emotion == null ? "" : emotion.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "高风险", "HIGH", "HIGH_RISK" -> EmotionLabel.HIGH_RISK;
            case "低落", "DEPRESSED", "SAD" -> EmotionLabel.DEPRESSED;
            case "焦虑", "ANXIETY", "ANXIOUS" -> EmotionLabel.ANXIETY;
            default -> EmotionLabel.NORMAL;
        };
    }

    private double scoreForEmotion(EmotionLabel emotion) {
        return switch (emotion) {
            case HIGH_RISK -> 4.0;
            case DEPRESSED -> 3.0;
            case ANXIETY -> 2.0;
            case NORMAL -> 0.0;
        };
    }

    private MultimodalSignal unsupported(FilePart media) {
        String contentType = media.headers().getContentType() == null
                ? "unknown"
                : media.headers().getContentType().toString();
        return new MultimodalSignal(
                "visual",
                EmotionLabel.NORMAL,
                0.0,
                0.45,
                "视觉分析：当前文件 " + media.filename() + " (" + contentType + ") 无法作为图片解码；如需视频逐帧 Face Mesh，请启用 MEDIAPIPE_MODE=http 对接外部 MediaPipe 服务。");
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Rect(int x, int y, int width, int height) {
        Rect bounded(int imageWidth, int imageHeight) {
            int boundedX = Math.max(0, Math.min(x, imageWidth - 1));
            int boundedY = Math.max(0, Math.min(y, imageHeight - 1));
            int boundedWidth = Math.max(1, Math.min(width, imageWidth - boundedX));
            int boundedHeight = Math.max(1, Math.min(height, imageHeight - boundedY));
            return new Rect(boundedX, boundedY, boundedWidth, boundedHeight);
        }

        Rect slice(double top, double bottom, double left, double right) {
            int nextX = x + (int) Math.round(width * left);
            int nextY = y + (int) Math.round(height * top);
            int nextWidth = Math.max(1, (int) Math.round(width * (right - left)));
            int nextHeight = Math.max(1, (int) Math.round(height * (bottom - top)));
            return new Rect(nextX, nextY, nextWidth, nextHeight);
        }

        int area() {
            return width * height;
        }
    }

    private record RegionStats(double brightness, double saturation, double contrast, double darkRatio) {
        RegionStats merge(RegionStats other) {
            return new RegionStats(
                    (brightness + other.brightness) / 2.0,
                    (saturation + other.saturation) / 2.0,
                    (contrast + other.contrast) / 2.0,
                    (darkRatio + other.darkRatio) / 2.0);
        }
    }

    private record VisionFeatures(
            boolean faceDetected,
            double faceCoverage,
            double symmetry,
            double faceBrightness,
            double saturation,
            double browShadow,
            double eyeDarkness,
            double mouthDown,
            double muscleTension
    ) {
    }
}
