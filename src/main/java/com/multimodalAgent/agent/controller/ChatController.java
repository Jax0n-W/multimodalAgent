package com.multimodalAgent.agent.controller;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.service.ChatService;
import com.multimodalAgent.agent.service.multimodal.MultimodalInputService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
/**
 * 学生聊天接口。
 *
 * <p>只允许学生账号发起对话，返回 SSE 流式事件供前端逐字显示。</p>
 */
public class ChatController {

    private final ChatService chatService;
    private final MultimodalInputService multimodalInputService;

    public ChatController(ChatService chatService, MultimodalInputService multimodalInputService) {
        this.chatService = chatService;
        this.multimodalInputService = multimodalInputService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChatRequest request
    ) {
        rejectAdmin(currentUser);
        return chatService.streamChat(currentUser.getId(), request);
    }

    @PostMapping(value = "/multimodal/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "audio", required = false) Mono<FilePart> audio,
            @RequestPart(value = "image", required = false) Mono<FilePart> image,
            @RequestPart(value = "video", required = false) Mono<FilePart> video
    ) {
        rejectAdmin(currentUser);
        boolean hasText = message != null && !message.isBlank();
        boolean hasAnyFile = audio != null || image != null || video != null;
        if (!hasText && !hasAnyFile) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少输入文字或上传一个多模态文件。");
        }
        String text = hasText ? message.trim() : "学生上传了多模态内容，希望获得支持。";
        ChatRequest request = new ChatRequest(sessionId, text);
        return multimodalInputService.analyze(text, monoOrEmpty(audio), monoOrEmpty(image), monoOrEmpty(video))
                .flatMapMany(analysis -> chatService.streamMultimodal(currentUser.getId(), request, analysis));
    }

    private void rejectAdmin(CurrentUser currentUser) {
        // 管理员后台只用于查看记录和工具状态，不能以管理员身份生成学生对话。
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (isAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员账号只能查看后台记录，不能发起学生对话。");
        }
    }

    private Mono<FilePart> monoOrEmpty(Mono<FilePart> part) {
        return part == null ? Mono.empty() : part;
    }
}
