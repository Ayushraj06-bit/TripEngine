package com.rideflow.location.internal.web;

import com.rideflow.identity.Role;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Authenticates and authorises STOMP frames.
 *
 * <p>A WebSocket carries no {@code Authorization} header per message, so the token arrives once in
 * the CONNECT frame and the resulting principal is bound to the session. Everything after that is
 * checked against that principal, never against anything the client sends.
 *
 * <p>This is not a hand-written JWT filter, which PROJECT_SPEC section 3 forbids. It delegates to
 * the same {@link JwtDecoder} the HTTP resource server uses, so signature, expiry and issuer are
 * verified by one implementation - a token rejected over HTTP is rejected here for the same reason.
 *
 * <p>Section 10 sets the destination rules, and both matter:
 *
 * <ul>
 *   <li>A client may only SEND to the {@code /app} destinations its role owns. Without this any
 *       authenticated rider could push driver positions.
 *   <li>A client may only SUBSCRIBE to its <em>own</em> {@code /user/queue} destinations. This is
 *       the one that stops a rider tailing another rider's trip.
 * </ul>
 */
@Component
class StompAuthInterceptor implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

  private static final String BEARER = "Bearer ";

  /** Destinations a driver may send to. */
  private static final List<String> DRIVER_SEND_DESTINATIONS = List.of("/app/driver/");

  /** Destinations a rider may send to. Empty for now; riders only ever subscribe. */
  private static final List<String> RIDER_SEND_DESTINATIONS = List.of();

  private final JwtDecoder jwtDecoder;
  private final JwtAuthenticationConverter authenticationConverter;

  StompAuthInterceptor(JwtDecoder jwtDecoder, JwtAuthenticationConverter authenticationConverter) {
    this.jwtDecoder = jwtDecoder;
    this.authenticationConverter = authenticationConverter;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }

    return switch (accessor.getCommand()) {
      case CONNECT -> authenticate(message, accessor);
      case SEND -> authoriseSend(message, accessor);
      case SUBSCRIBE -> authoriseSubscribe(message, accessor);
      default -> message;
    };
  }

  private Message<?> authenticate(Message<?> message, StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (header == null || !header.startsWith(BEARER)) {
      throw new MessagingSecurityException("CONNECT requires an Authorization bearer token");
    }

    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(header.substring(BEARER.length()));
    } catch (JwtException e) {
      // The message deliberately does not say why: expired, tampered and foreign-key
      // tokens are all just "not valid" to the client.
      log.debug("Rejected STOMP CONNECT: {}", e.getMessage());
      throw new MessagingSecurityException("Token is not valid");
    }

    AbstractAuthenticationToken authentication = authenticationConverter.convert(jwt);
    if (authentication == null) {
      throw new MessagingSecurityException("Token is not valid");
    }
    // Bound to the session: every later frame is checked against this, not against
    // anything the client repeats.
    accessor.setUser(authentication);
    // The session lives no longer than the token it was opened with; see ExpiringSessions.
    if (accessor.getSessionAttributes() != null && jwt.getExpiresAt() != null) {
      accessor.getSessionAttributes().put(ExpiringSessions.TOKEN_EXPIRES_AT, jwt.getExpiresAt());
    }
    return message;
  }

  private Message<?> authoriseSend(Message<?> message, StompHeaderAccessor accessor) {
    Authentication user = requireAuthenticated(accessor);
    String destination = accessor.getDestination();
    if (destination == null) {
      throw new MessagingSecurityException("SEND requires a destination");
    }

    boolean permitted = allowedSendPrefixes(user).stream().anyMatch(destination::startsWith);
    if (!permitted) {
      log.debug("Rejected SEND to {} for {}", destination, user.getName());
      throw new MessagingSecurityException("Not permitted to send to " + destination);
    }
    return message;
  }

  private Message<?> authoriseSubscribe(Message<?> message, StompHeaderAccessor accessor) {
    Authentication user = requireAuthenticated(accessor);
    String destination = accessor.getDestination();
    if (destination == null) {
      throw new MessagingSecurityException("SUBSCRIBE requires a destination");
    }

    // Spring rewrites /user/** to the caller's own queue, so a client asking for
    // /user/queue/trip can only ever reach its own. Anything else under /user is a
    // client trying to name somebody else's destination explicitly.
    if (destination.startsWith("/user/queue/")) {
      return message;
    }

    log.debug("Rejected SUBSCRIBE to {} for {}", destination, user.getName());
    throw new MessagingSecurityException(
        "Only your own /user/queue destinations may be subscribed to");
  }

  private static List<String> allowedSendPrefixes(Authentication user) {
    boolean isDriver =
        user.getAuthorities().stream()
            .anyMatch(authority -> Role.DRIVER.authority().equals(authority.getAuthority()));
    return isDriver ? DRIVER_SEND_DESTINATIONS : RIDER_SEND_DESTINATIONS;
  }

  private static Authentication requireAuthenticated(StompHeaderAccessor accessor) {
    if (accessor.getUser() instanceof Authentication authentication) {
      return authentication;
    }
    throw new MessagingSecurityException("Not authenticated");
  }

  /**
   * Aborts the frame; Spring answers with a STOMP ERROR frame.
   *
   * <p>A {@link org.springframework.messaging.MessagingException} rather than a plain runtime
   * exception: the channel wraps anything else in a generic delivery failure, and the client would
   * see "failed to send message" instead of why.
   */
  static class MessagingSecurityException extends org.springframework.messaging.MessagingException {
    private static final long serialVersionUID = 1L;

    MessagingSecurityException(String message) {
      super(message);
    }
  }
}
