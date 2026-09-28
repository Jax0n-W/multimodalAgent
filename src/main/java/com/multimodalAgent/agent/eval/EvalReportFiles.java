package com.multimodalAgent.agent.eval;

import java.nio.file.Path;

public record EvalReportFiles(Path results, Path summary, Path markdown) {
}
