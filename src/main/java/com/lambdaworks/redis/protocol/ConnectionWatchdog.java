// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.protocol;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Supplier;
import com.lambdaworks.redis.ConnectionEvents;
import com.lambdaworks.redis.RedisChannelInitializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import io.netty.util.internal.logging.InternalLogLevel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A netty {@link ChannelHandler} responsible for monitoring the channel and reconnecting when the connection is lost.
 * 
 * @author Will Glozer
 */
@ChannelHandler.Sharable
public class ConnectionWatchdog extends ChannelInboundHandlerAdapter implements TimerTask {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ConnectionWatchdog.class);
    public static final long LOGGING_QUIET_TIME_MS = TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS);

    public static final int RETRY_TIMEOUT_MAX = 14;
    private Bootstrap bootstrap;
    private Channel channel;
    private Timer timer;
    private boolean reconnect;
    private boolean doNotReconnect;
    private int attempts;
    private SocketAddress remoteAddress;
    private Supplier<SocketAddress> socketAddressSupplier;
    private long lastReconnectionLogging = -1;

    /**
     * Create a new watchdog that adds to new connections to the supplied {@link ChannelGroup} and establishes a new
     * {@link Channel} when disconnected, while reconnect is true.
     * 
     * @param bootstrap Configuration for new channels.
     * @param timer Timer used for delayed reconnect.
     */
    public ConnectionWatchdog(Bootstrap bootstrap, Timer timer) {
        this(bootstrap, timer, null);
    }

    /**
     * Create a new watchdog that adds to new connections to the supplied {@link ChannelGroup} and establishes a new
     * {@link Channel} when disconnected, while reconnect is true. The socketAddressSupplier can supply the reconnect address.
     * 
     * @param bootstrap Configuration for new channels.
     * @param timer Timer used for delayed reconnect.
     * @param socketAddressSupplier the socket address suplier for gaining an address to reconnect to
     */
    public ConnectionWatchdog(Bootstrap bootstrap, Timer timer, Supplier<SocketAddress> socketAddressSupplier) {
        this.bootstrap = bootstrap;
        this.timer = timer;
        this.socketAddressSupplier = socketAddressSupplier;
    }

    public void setReconnect(boolean reconnect) {
        this.reconnect = reconnect;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        logger.debug("userEventTriggered(" + ctx + ", " + evt + ")");
        if (evt instanceof ConnectionEvents.PrepareClose) {
            ConnectionEvents.PrepareClose prepareClose = (ConnectionEvents.PrepareClose) evt;
            setReconnect(false);
            prepareClose.getPrepareCloseFuture().set(true);
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

        logger.debug("channelActive(" + ctx + ")");
        channel = ctx.channel();
        attempts = 0;
        remoteAddress = channel.remoteAddress();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        logger.debug("channelInactive(" + ctx + ")");
        channel = null;
        if (reconnect && !doNotReconnect) {
            scheduleReconnect();
        }
        super.channelInactive(ctx);
    }

    private void scheduleReconnect() {

        if (channel == null || !channel.isActive()) {
            logger.debug("scheduleReconnect()");
            if (attempts < RETRY_TIMEOUT_MAX) {
                attempts++;
            }
            int timeout = 2 << attempts;
            timer.newTimeout(this, timeout, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Reconnect to the remote address that the closed channel was connected to. This creates a new {@link ChannelPipeline} with
     * the same handler instances contained in the old channel's pipeline.
     * 
     * @param timeout Timer task handle.
     * 
     * @throws Exception when reconnection fails.
     */
    @Override
    public void run(Timeout timeout) throws Exception {

        boolean shouldLog = shouldLog();

        InternalLogLevel infoLevel = InternalLogLevel.INFO;
        InternalLogLevel warnLevel = InternalLogLevel.WARN;

        if (shouldLog) {
            lastReconnectionLogging = System.currentTimeMillis();
        } else {
            warnLevel = InternalLogLevel.DEBUG;
            infoLevel = InternalLogLevel.DEBUG;
        }

        try {
            reconnect(infoLevel, warnLevel);
        } catch (Exception e) {
            logger.log(warnLevel, "Cannot connect: " + e.toString());
            scheduleReconnect();
        }

    }

    private void reconnect(InternalLogLevel infoLevel, InternalLogLevel warnLevel) throws InterruptedException {
        logger.log(infoLevel, "Reconnecting, last destination was " + remoteAddress);

        if (socketAddressSupplier != null) {
            try {
                remoteAddress = socketAddressSupplier.get();
            } catch (RuntimeException e) {
                logger.log(warnLevel, "Cannot retrieve the current address from socketAddressSupplier: " + e.toString());
            }
        }

        ChannelFuture connect = bootstrap.connect(remoteAddress);
        connect.sync().await();

        try {
            doNotReconnect = true;
            RedisChannelInitializer channelInitializer = connect.channel().pipeline().get(RedisChannelInitializer.class);
            channelInitializer.channelInitialized().get();
            doNotReconnect = false;
            logger.log(infoLevel, "Reconnected to " + remoteAddress);
        } catch (Exception e) {
            doNotReconnect = true;
            logger.error("Cannot initialize channel. Disabling autoReconnect", e);
        }

    }

    private boolean shouldLog() {

        long quietUntil = lastReconnectionLogging + LOGGING_QUIET_TIME_MS;

        if (quietUntil > System.currentTimeMillis()) {
            return false;
        }

        return true;
    }
}
