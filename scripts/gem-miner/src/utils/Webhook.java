package utils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class Webhook {
  public interface DataProvider {
    WebhookData snapshot();
  }

  public record WebhookConfig(String webhookUrl, int intervalMinutes, boolean enabled) {}
  public record WebhookData(int ash, long xpGained, int levelsGained, String runtimeText, int intervalMinutes) {}

  private final DataProvider dataProvider;
  private final Consumer<String> logger;
  private final ConcurrentLinkedQueue<String> pendingEvents = new ConcurrentLinkedQueue<>();

  private volatile WebhookConfig config = new WebhookConfig(null, 30, false);
  private volatile boolean submitted = false;
  private volatile boolean startSent = false;
  private volatile boolean shutdownHookRegistered = false;
  private volatile long lastSentMs = 0;

  public Webhook(DataProvider dataProvider, Consumer<String> logger) {
    this.dataProvider = Objects.requireNonNull(dataProvider);
    this.logger = logger != null ? logger : s -> {};
  }

  public boolean isSubmitted() {
    return submitted;
  }

  public int getIntervalMinutes() {
    return config != null ? config.intervalMinutes() : 0;
  }

  public WebhookConfig getConfig() {
    return config;
  }

  public void applyConfig(WebhookConfig config) {
    if (config == null) {
      this.config = new WebhookConfig(null, 30, false);
      submitted = false;
      return;
    }
    this.config = config;
    submitted = true;
    lastSentMs = System.currentTimeMillis();
  }

  public void ensureStarted(Runnable stopHook) {
    if (!startSent) {
      pendingEvents.add("Started");
      startSent = true;
      lastSentMs = System.currentTimeMillis();
      if (!shutdownHookRegistered && stopHook != null) {
        Runtime.getRuntime().addShutdownHook(new Thread(stopHook));
        shutdownHookRegistered = true;
      }
    }
  }

  public void enqueueEvent(String label) {
    pendingEvents.add(label == null ? "" : label);
  }

  public void queuePeriodicWebhookIfDue() {
    if (config == null || !config.enabled() || config.webhookUrl() == null || config.webhookUrl().isBlank()) {
      return;
    }
    long intervalMs = config.intervalMinutes() * 60_000L;
    if (intervalMs <= 0) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - lastSentMs >= intervalMs) {
      pendingEvents.add("Periodic");
    }
  }

  public void dispatchPendingWebhooks() {
    while (true) {
      String event = pendingEvents.poll();
      if (event == null) {
        return;
      }
      String label = event.isEmpty() ? null : event;
      WebhookData data = dataProvider.snapshot();
      if (data == null) {
        return;
      }
      sendWebhookUpdateAsync(data, label);
    }
  }

  private void sendWebhookUpdateAsync(WebhookData data, String eventLabel) {
    if (data == null) {
      return;
    }
    new Thread(() -> sendWebhookUpdate(data, eventLabel), "volcanic-ash-webhook-sender").start();
  }

  private void sendWebhookUpdate(WebhookData data, String eventLabel) {
    if (config == null || !config.enabled() || config.webhookUrl() == null || config.webhookUrl().isBlank()) {
      log("WEBHOOK", "Skip send: disabled or missing URL");
      return;
    }

    String normalizedUrl = normalizeWebhookUrl(config.webhookUrl());
    try {
      String payloadJson = buildWebhookPayload(data, eventLabel);
      HttpURLConnection conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
      conn.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
      byte[] payload = payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      conn.setFixedLengthStreamingMode(payload.length);
      conn.getOutputStream().write(payload);

      int code = conn.getResponseCode();
      if (code == 200 || code == 204) {
        log("WEBHOOK", "Sent webhook (" + config.intervalMinutes() + "m interval)");
        lastSentMs = System.currentTimeMillis();
      } else {
        String body = readResponseBody(conn);
        log("WEBHOOK", "Webhook failed HTTP " + code + (body != null ? (" body=" + body) : ""));
      }
    } catch (Exception e) {
      log("WEBHOOK", "Error sending webhook: " + e.getMessage());
    }
  }

  private String buildWebhookPayload(WebhookData data, String eventLabel) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"embeds\":[{\"title\":\"Gem Miner");
    if (eventLabel != null && !eventLabel.isBlank()) {
      sb.append(" (").append(eventLabel).append(")");
    }
    sb.append("\",\"fields\":[");
    sb.append("{\"name\":\"Gems collected\",\"value\":\"").append(data.ash()).append("\",\"inline\":true},");
    sb.append("{\"name\":\"Mining XP gained\",\"value\":\"").append(data.xpGained()).append("\",\"inline\":true},");
    sb.append("{\"name\":\"Levels gained\",\"value\":\"").append(data.levelsGained()).append("\",\"inline\":true},");
    sb.append("{\"name\":\"Runtime\",\"value\":\"").append(data.runtimeText()).append("\",\"inline\":true}");
    sb.append("]}]}");
    return sb.toString();
  }

  private String normalizeWebhookUrl(String url) {
    if (url == null) {
      return null;
    }
    if (url.startsWith("https://discordapp.com/")) {
      return "https://discord.com/" + url.substring("https://discordapp.com/".length());
    }
    return url;
  }

  private String readResponseBody(HttpURLConnection conn) {
    InputStream stream = null;
    try {
      stream = conn.getErrorStream();
      if (stream == null) {
        stream = conn.getInputStream();
      }
      if (stream == null) {
        return null;
      }
      try (BufferedInputStream bis = new BufferedInputStream(stream);
           ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        bis.transferTo(baos);
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
      }
    } catch (Exception e) {
      return null;
    }
  }

  private void log(String tag, String message) {
    logger.accept(tag + ": " + message);
  }
}
