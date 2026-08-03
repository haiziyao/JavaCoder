package com.jcoder.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 作者：亥子曜
 * -后之览者，亦将有感于斯文
 */
public class AgentEventQueue {

    private final BlockingQueue<AgentEvent> queue;
    public AgentEventQueue(int capacity) {
        queue = new ArrayBlockingQueue<AgentEvent>(capacity);
    }

    public void putSafe(AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public AgentEvent take() throws InterruptedException {
        return queue.take();
    }
}
