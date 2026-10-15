package com.multimodalAgent.agent.service.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiebaTextTokenizerTest {

    private final JiebaTextTokenizer tokenizer = new JiebaTextTokenizer();

    @Test
    void segmentsChineseSearchTermsAndNormalizesAscii() {
        List<String> chinese = tokenizer.tokenize("失眠影响学习，如何调整睡眠作息？");
        List<String> ascii = tokenizer.tokenize("ＢＧＥ-M3 Embedding");

        assertTrue(chinese.contains("失眠"));
        assertTrue(chinese.contains("睡眠"));
        assertTrue(chinese.contains("han:睡"));
        assertFalse(chinese.contains("如何"));
        assertTrue(ascii.contains("bge"));
        assertTrue(ascii.contains("m3"));
        assertTrue(ascii.contains("embedding"));
    }

    @Test
    void keepsColloquialOutOfVocabularyRecallWithoutOldBigramSplitting() {
        List<String> terms = tokenizer.tokenize("睡不着怎么办");

        assertTrue(terms.contains("han:睡"));
        assertFalse(terms.contains("怎么办"));
        assertFalse(terms.contains("睡不"));
    }
}
