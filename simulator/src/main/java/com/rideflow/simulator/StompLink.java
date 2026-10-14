package com.rideflow.simulator;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One app's STOMP connection: the JWT in the CONNECT frame, its {@code /user/queue} subscriptions,
 * and word to the owner when the connection is lost - by the network, or by the backend closing it
 * as the token it was opened with expires.
 *
 * <p>Messages and losses arrive on the client's threads; the owner hands them to its own.
 */
public final class StompLink {

  private static final Logger log = LoggerFactory.getLogger(StompLink.class);

  private static final long CONNECT_TIMEOUT_SECONDS = 10;

  /** The ERROR frame Spring answers a client's DISCONNECT with. */
  private static final String SESSION_CLOSED = "Session closed.";

  /** What the owner hears. */
  public interface Listener {

    /** A message's envelope: {@code {type, tripId, seq, sentAt, payload}}. */
    void onMessage(String destination, JsonNode envelope);

    void onLost();
  }

  private final WebSocketStompClient client;
  private final String url;
  private final JsonMapper json;
  private volatile StompSession session;

  public StompLink(WebSocketStompClient client, String url, JsonMapper json) {
    this.client = client;
    this.url = url;
    this.json = json;
  }

  /**
   * Connects and subscribes, waiting for CONNECTED.
   *
   * @throws IllegalStateException if the backend refuses or does not answer in time
   */
  public void connect(String accessToken, List<String> destinations, Listener listener) {
    StompHeaders connect = new StompHeaders();
    connect.add("Authorization", "Bearer " + accessToken);
    StompSession opened;
    try {
      opened =
          client
              .connectAsync(url, new WebSocketHttpHeaders(), connect, handler(listener))
              .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("STOMP connect failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while connecting", e);
    }
    session = opened;
    for (String destination : destinations) {
      opened.subscribe(destination, frames(destination, listener));
    }
  }

  /** Sends {@code payload} as JSON; false when there is no connection to send it on. */
  public boolean send(String destination, Object payload) {
    StompSession current = session;
    if (current == null || !current.isConnected()) {
      return false;
    }
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
    current.send(headers, json.writeValueAsBytes(payload));
    return true;
  }

  /** Closes the connection on purpose: the owner is not told it was lost. */
  public void disconnect() {
    StompSession current = session;
    session = null;
    if (current != null && current.isConnected()) {
      current.disconnect();
    }
  }

  public boolean connected() {
    StompSession current = session;
    return current != null && current.isConnected();
  }

  private StompSessionHandlerAdapter handler(Listener listener) {
    return new StompSessionHandlerAdapter() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }

      /** Frames reaching the session rather than a subscription are ERRORs. */
      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        String message = headers.getFirst("message");
        if (SESSION_CLOSED.equals(message)) {
          // How Spring acknowledges our own DISCONNECT: not a fault.
          log.debug("STOMP session closed");
        } else {
          log.warn("STOMP error from the backend: {}", message);
        }
      }

      @Override
      public void handleException(
          StompSession failed,
          StompCommand command,
          StompHeaders headers,
          byte[] payload,
          Throwable exception) {
        log.warn("Could not handle a STOMP {} frame", command, exception);
      }

      @Override
      public void handleTransportError(StompSession lost, Throwable exception) {
        // Only the live connection's loss matters; a closed one's late news does not.
        if (lost == session) {
          session = null;
          listener.onLost();
        }
      }
    };
  }

  private StompFrameHandler frames(String destination, Listener listener) {
    return new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        listener.onMessage(
            destination, json.readTree(new String((byte[]) payload, StandardCharsets.UTF_8)));
      }
    };
  }
}
