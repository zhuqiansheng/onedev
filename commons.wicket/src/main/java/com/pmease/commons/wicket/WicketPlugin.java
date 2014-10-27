package com.pmease.commons.wicket;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.wicket.Application;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.protocol.ws.api.registry.SimpleWebSocketConnectionRegistry;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmease.commons.loader.AbstractPlugin;
import com.pmease.commons.wicket.websocket.WebSocketMessage;

public class WicketPlugin extends AbstractPlugin {

	private final Application application;
	
	private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
	
	private final ObjectMapper objectMapper;
	
	private final WebSocketPolicy webSocketPolicy;
	
	@Inject
	public WicketPlugin(Application application, ObjectMapper objectMapper, WebSocketPolicy webSocketPolicy) {
		this.application = application;
		this.objectMapper = objectMapper;
		this.webSocketPolicy = webSocketPolicy;
	}
	
	@Override
	public void start() {
		final String keepAliveMessage;
		try {
			WebSocketMessage message = new WebSocketMessage(WebSocketMessage.KEEP_ALIVE, null);
			keepAliveMessage = objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
		
		executorService.scheduleWithFixedDelay(new Runnable() {

			@Override
			public void run() {
				for (IWebSocketConnection connection: new SimpleWebSocketConnectionRegistry().getConnections(application)) {
					if (connection != null && connection.isOpen()) {
						try {
							connection.sendMessage(keepAliveMessage);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				}
			}
			
		}, 0, webSocketPolicy.getIdleTimeout()/2, TimeUnit.MILLISECONDS);
	}

	@Override
	public void stop() {
		executorService.shutdown();
	}

}