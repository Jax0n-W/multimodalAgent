package com.multimodalAgent.agent.service.mcp;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class LogAlertNotifierTest {

    @Test
    void dryRunLogOmitsRecipientUserAndPsychologicalSummary(CapturedOutput output) {
        UserAccount user = new UserAccount();
        user.setUsername("private-student");
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSummary("private psychological consultation summary");
        AlertRecord alert = new AlertRecord();
        alert.setRecipient("private-recipient@example.com");

        new LogAlertNotifier().notify(alert, report);

        assertTrue(output.getOut().contains("High risk alert dry-run completed"));
        assertFalse(output.getOut().contains("private-student"));
        assertFalse(output.getOut().contains("private-recipient@example.com"));
        assertFalse(output.getOut().contains("private psychological consultation summary"));
    }
}
