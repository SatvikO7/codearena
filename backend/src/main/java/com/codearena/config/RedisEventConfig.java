package com.codearena.config;

import com.codearena.events.SubmissionEventSubscriber;
import com.codearena.shared.SubmissionEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Subscribes the API server to submission events.
 *
 * <p>Every API instance subscribes to the same channel and each one forwards to whichever
 * browsers happen to be connected to <em>it</em>. That is what makes the design work behind
 * a load balancer without sticky sessions: a worker publishes once, every instance hears it,
 * and only the instances with a relevant open stream do anything.
 *
 * <p>Pub/Sub delivers to whoever is listening at that moment and keeps nothing. An instance
 * that is restarting misses the message entirely. That is tolerable only because the
 * database is authoritative: the affected browser still converges through the snapshot it
 * receives on reconnect and the bounded fallback poll. It would not be tolerable if this
 * were the only path to the truth.
 */
@Configuration
public class RedisEventConfig {

    /**
     * Listener threads.
     *
     * <p>Small on purpose. This pool only parses a message, does one indexed read and writes
     * to open streams; making it large would spend threads on work that is almost entirely
     * waiting on other people's sockets.
     */
    @Bean
    public ThreadPoolTaskExecutor submissionEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("submission-events-");
        // A dropped event is a delayed browser update, not a lost verdict, so a saturated
        // queue must not become back-pressure on Redis or on the judging pipeline.
        executor.setRejectedExecutionHandler((runnable, pool) -> { /* discard */ });
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer submissionEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            SubmissionEventSubscriber subscriber,
            ThreadPoolTaskExecutor submissionEventExecutor) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(submissionEventExecutor);
        container.addMessageListener(subscriber, new ChannelTopic(SubmissionEvent.CHANNEL));
        return container;
    }
}
