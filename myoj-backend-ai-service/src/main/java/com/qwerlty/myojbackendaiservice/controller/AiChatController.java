package com.qwerlty.myojbackendaiservice.controller;

import com.qwerlty.myojbackendaiservice.chat.model.AiChatMessageView;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSendRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSessionRequest;
import com.qwerlty.myojbackendaiservice.chat.model.AiChatSessionView;
import com.qwerlty.myojbackendaiservice.chat.service.AiChatService;
import com.qwerlty.myojbackendaiservice.chat.service.UserIdentity;
import com.qwerlty.myojbackendaiservice.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/chat")
public class AiChatController {

    private final AiChatService chatService;

    public AiChatController(AiChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/session/get")
    public ApiResponse<AiChatSessionView> getSession(@Valid @RequestBody AiChatSessionRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.success(chatService.getSession(UserIdentity.requireUserId(request), body));
    }

    @PostMapping("/session/clear")
    public ApiResponse<Boolean> clearSession(@Valid @RequestBody AiChatSessionRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.success(chatService.clearSession(UserIdentity.requireUserId(request), body));
    }

    @PostMapping("/message/send")
    public ApiResponse<AiChatMessageView> sendMessage(@Valid @RequestBody AiChatSendRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.success(chatService.chat(UserIdentity.requireUserId(request), body));
    }

    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@Valid @RequestBody AiChatSendRequest body,
                                    HttpServletRequest request) {
        return chatService.streamChat(UserIdentity.requireUserId(request), body);
    }
}
