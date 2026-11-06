package io.github.jayeshb.walletsmssync.inference;

import com.google.ai.edge.litertlm.*;
import java.util.Collections;

/** Pinned, typed native API boundary. No Wallet credentials or tool access enter this module. */
public final class NativeModel implements AutoCloseable {
    private Engine engine;
    private String backend;

    public static String probe() {
        Engine.Companion.setNativeMinLogServerity(LogSeverity.INFINITY);
        return "LiteRT-LM native runtime ready";
    }

    public NativeModel(String path, String cache, boolean preferGpu) {
        probe();
        if (preferGpu) {
            try { initialize(path, cache, Backend.GPU); return; }
            catch (RuntimeException | LinkageError failure) { release(); }
        }
        initialize(path, cache, Backend.CPU);
    }
    private void initialize(String path, String cache, Backend chosen) {
        engine = new Engine(new EngineConfig(path, chosen, null, null, 4096, cache));
        try { engine.initialize(); backend = chosen.name(); }
        catch (RuntimeException | LinkageError failure) { release(); throw failure; }
    }
    public String getBackend() { return backend; }
    public String generate(String prompt) {
        ConversationConfig config = new ConversationConfig(null, Collections.emptyList(), new SamplerConfig(1, 1.0, 0.0, 0));
        try (Conversation conversation = engine.createConversation(config)) {
            Message response = conversation.sendMessage(Message.Companion.of(prompt));
            StringBuilder text = new StringBuilder();
            for (Content content : response.getContents()) {
                if (content instanceof Content.Text) text.append(((Content.Text) content).getText());
            }
            return text.toString();
        }
    }
    private void release() {
        if (engine != null) {
            if (engine.isInitialized()) engine.close();
            engine = null;
        }
    }
    @Override public void close() { release(); }
}
