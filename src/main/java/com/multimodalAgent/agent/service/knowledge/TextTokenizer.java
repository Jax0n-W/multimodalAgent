package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Tokenization boundary used by lexical retrieval. */
public interface TextTokenizer {

    List<String> tokenize(String text);

    String description();
}
