package com.rideflow.payment.internal;

import com.rideflow.shared.RideFlowProperties.Payment.Outcome;

/**
 * What the gateway's webhook says about a payment.
 *
 * @param eventId the gateway's id for this notification; the same on every retry of it
 */
public record GatewayNotification(
    String eventId, String gatewayRef, Outcome status, long amountPaise) {}
