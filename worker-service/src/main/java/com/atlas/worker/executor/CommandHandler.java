package com.atlas.worker.executor;

import java.util.Map;

public interface CommandHandler {

    Map<String, Object> handle(String stepName, Map<String, Object> input);
}
