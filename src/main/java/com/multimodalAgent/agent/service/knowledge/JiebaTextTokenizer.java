package com.multimodalAgent.agent.service.knowledge;

import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.ArrayList;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Search-mode Jieba tokenizer with Unicode normalization for Chinese and ASCII text. */
@Component
public class JiebaTextTokenizer implements TextTokenizer {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "是", "在", "有", "就", "也", "都", "而", "及", "与",
            "或", "不", "吗", "呢", "啊", "吧", "时", "如何", "怎么", "怎么办",
            "怎样", "哪里", "哪些", "什么", "可以", "应该"
    );
    private static final String STOP_HAN_CHARACTERS =
            "的了和是在有就不也都而及与着或个没我你他她它时把被让给向从到中上下"
                    + "来里外很更最还又再才所这那哪怎什么吗呢啊吧呀得地";

    private final ThreadLocal<JiebaSegmenter> segmenters =
            ThreadLocal.withInitial(JiebaSegmenter::new);

    @Override
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<String> jiebaTerms = segmenters.get()
                .process(normalized, JiebaSegmenter.SegMode.SEARCH).stream()
                .map(token -> token.word == null ? "" : token.word.trim())
                .filter(token -> !token.isBlank())
                .filter(this::containsLetterOrDigit)
                .toList();
        List<String> terms = new ArrayList<>();
        for (String term : jiebaTerms) {
            if (STOP_WORDS.contains(term)) {
                continue;
            }
            terms.add(term);
            // Character backoff retains recall for colloquial/OOV forms such as “睡不着”
            // while Jieba words remain the primary precision-bearing terms.
            int[] codePoints = term.codePoints().toArray();
            if (codePoints.length < 2 || !isAllHan(codePoints)) {
                continue;
            }
            for (int codePoint : codePoints) {
                String character = new String(Character.toChars(codePoint));
                if (!STOP_HAN_CHARACTERS.contains(character)) {
                    terms.add("han:" + character);
                }
            }
        }
        return List.copyOf(terms);
    }

    @Override
    public String description() {
        return "Jieba search-mode segmentation with NFKC and Han OOV backoff";
    }

    private boolean containsLetterOrDigit(String token) {
        return token.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private boolean isAllHan(int[] codePoints) {
        for (int codePoint : codePoints) {
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }
}
