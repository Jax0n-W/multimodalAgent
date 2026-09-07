package com.multimodalAgent.agent.service.multimodal;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
/**
 * 多模态接入层：统一文本、语音和图像/视频输入。
 */
public class MultimodalInputService {

    private final WhisperClient whisperClient;
    private final MediaPipeClient mediaPipeClient;
    private final PsychologicalAssessmentService assessmentService;
    private final MultimodalFusionService fusionService;

    public MultimodalInputService(
            WhisperClient whisperClient,
            MediaPipeClient mediaPipeClient,
            PsychologicalAssessmentService assessmentService,
            MultimodalFusionService fusionService
    ) {
        this.whisperClient = whisperClient;
        this.mediaPipeClient = mediaPipeClient;
        this.assessmentService = assessmentService;
        this.fusionService = fusionService;
    }

    public Mono<MultimodalAnalysis> analyze(
            String message,
            Mono<FilePart> audio,
            Mono<FilePart> image,
            Mono<FilePart> video
    ) {
        String userText = message == null ? "" : message.trim();
        List<Mono<MultimodalSignal>> tasks = new ArrayList<>();

        if (!userText.isBlank()) {
            tasks.add(Mono.fromCallable(() -> toTextSignal(userText)));
        }
        tasks.add(audio.flatMap(whisperClient::transcribe)
                .map(transcript -> toAudioSignal(transcript))
                .onErrorResume(ignored -> Mono.empty()));
        tasks.add(image.flatMap(mediaPipeClient::analyze)
                .onErrorResume(ignored -> Mono.empty()));
        tasks.add(video.flatMap(mediaPipeClient::analyze)
                .onErrorResume(ignored -> Mono.empty()));

        return Flux.merge(tasks)
                .collectList()
                .map(signals -> fusionService.fuse(userText, signals));
    }

    private MultimodalSignal toTextSignal(String text) {
        PsychologyAssessment assessment = assessmentService.assess(text);
        return new MultimodalSignal(
                "text",
                assessment.emotion(),
                assessment.emotionScore(),
                assessment.confidence(),
                "文本情绪模型：" + assessment.summary());
    }

    private MultimodalSignal toAudioSignal(String transcript) {
        PsychologyAssessment assessment = assessmentService.assess(transcript);
        EmotionLabel emotion = assessment.emotion();
        return new MultimodalSignal(
                "audio",
                emotion,
                assessment.emotionScore(),
                Math.min(0.9, assessment.confidence()),
                "Whisper 转写后情绪分析：" + transcript);
    }
}
