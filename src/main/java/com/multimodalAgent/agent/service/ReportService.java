package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.AlertRecordResponse;
import com.multimodalAgent.agent.dto.ConversationResponse;
import com.multimodalAgent.agent.dto.ExcelRecordResponse;
import com.multimodalAgent.agent.repository.AlertRecordRepository;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * 管理员后台数据查询服务。
 *
 * <p>封装报告列表、Excel 写入记录、预警记录和完整会话读取逻辑。</p>
 */
public class ReportService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final PsychologicalReportRepository psychologicalReportRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AlertRecordRepository alertRecordRepository;

    public ReportService(
            PsychologicalReportRepository psychologicalReportRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            AlertRecordRepository alertRecordRepository
    ) {
        this.psychologicalReportRepository = psychologicalReportRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.alertRecordRepository = alertRecordRepository;
    }

    @Transactional(readOnly = true)
    public List<PsychologicalReport> myReports(Long userId) {
        return psychologicalReportRepository.findTop50ByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<PsychologicalReport> latestReports() {
        // 管理员后台只展示学生对话产生的报告，避免管理员测试消息混入统计大屏。
        return psychologicalReportRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(ReportService::isStudentReport)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExcelRecordResponse> excelRecords() {
        return psychologicalReportRepository
                .findTop100ByExcelStatusOrderByCreatedAtDesc(ToolStatus.SUCCESS)
                .stream()
                .filter(ReportService::isStudentReport)
                .map(ExcelRecordResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlertRecordResponse> alertRecords() {
        return alertRecordRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(alertRecord -> isStudentReport(alertRecord.getReport()))
                .map(AlertRecordResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse conversation(String sessionId) {
        ChatSession session = chatSessionRepository.findByPublicId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        // 管理员只能查看学生会话；非学生会话统一按不存在处理，减少后台数据误展示。
        if (!isStudentUser(session.getUser())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        List<ChatMessage> messages = chatMessageRepository.findBySession_PublicIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(message -> message.getRole() != MessageRole.SYSTEM)
                .toList();
        return ConversationResponse.from(session, messages);
    }

    private static boolean isStudentReport(PsychologicalReport report) {
        return report != null && isStudentUser(report.getUser());
    }

    private static boolean isStudentUser(UserAccount user) {
        return user != null && !user.getRoles().contains(ROLE_ADMIN);
    }
}
