/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.rsocket.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.ResponderRSocket;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import org.springframework.cloud.gateway.rsocket.autoconfigure.BrokerProperties;
import org.springframework.cloud.gateway.rsocket.common.metadata.TagsMetadata;
import org.springframework.cloud.gateway.rsocket.common.metadata.TagsMetadata.Key;
import org.springframework.cloud.gateway.rsocket.route.Route;
import org.springframework.cloud.gateway.rsocket.route.Routes;
import org.springframework.cloud.gateway.rsocket.routing.LoadBalancerFactory;
import org.springframework.messaging.rsocket.MetadataExtractor;

import static org.springframework.cloud.gateway.rsocket.core.GatewayExchange.ROUTE_ATTR;
import static org.springframework.cloud.gateway.rsocket.core.GatewayExchange.Type.FIRE_AND_FORGET;
import static org.springframework.cloud.gateway.rsocket.core.GatewayExchange.Type.REQUEST_CHANNEL;
import static org.springframework.cloud.gateway.rsocket.core.GatewayExchange.Type.REQUEST_RESPONSE;
import static org.springframework.cloud.gateway.rsocket.core.GatewayExchange.Type.REQUEST_STREAM;
import static org.springframework.cloud.gateway.rsocket.core.GatewayFilterChain.executeFilterChain;

/**
 * Acts as a proxy to other registered sockets. Creates a GatewayExchange and attempts to
 * locate a Route. If a Route is found, it is added to the exchange and the filter chains
 * is executed againts the Route's filters. If the filter chain is successful, an attempt
 * to locate a target RSocket via the Registry is executed. If not found a pending RSocket
 * is returned.
 */
public class GatewayRSocket extends AbstractGatewayRSocket {

	private static final Log log = LogFactory.getLog(GatewayRSocket.class);

	private final Routes routes;

	private final PendingRequestRSocketFactory pendingFactory;

	private final LoadBalancerFactory loadBalancerFactory;

	GatewayRSocket(Routes routes, PendingRequestRSocketFactory pendingFactory,
			LoadBalancerFactory loadBalancerFactory, MeterRegistry meterRegistry,
			BrokerProperties properties, MetadataExtractor metadataExtractor,
			TagsMetadata metadata) {
		super(meterRegistry, properties, metadataExtractor, metadata);
		this.routes = routes;
		this.pendingFactory = pendingFactory;
		this.loadBalancerFactory = loadBalancerFactory;
	}

	protected PendingRequestRSocketFactory getPendingFactory() {
		return this.pendingFactory;
	}

	@Override
	public Mono<Void> fireAndForget(Payload payload) {
		GatewayExchange exchange = createExchange(FIRE_AND_FORGET, payload);
		return findRSocketOrCreatePending(exchange).flatMap(rSockets -> {
			retain(payload, rSockets);
			List<Mono<Void>> results = rSockets.stream()
					.map(rSocket -> rSocket.fireAndForget(payload))
					.collect(Collectors.toList());
			// TODO: this looks weird
			return Flux.merge(results).then();
		}).doOnError(t -> count(exchange, "error")).doFinally(s -> count(exchange, ""));
	}

	@Override
	public Flux<Payload> requestChannel(Payload payload, Publisher<Payload> payloads) {
		GatewayExchange exchange = createExchange(REQUEST_CHANNEL, payload);
		Tags responderTags = Tags.of("source", "responder");
		return findRSocketOrCreatePending(exchange).flatMapMany(rSockets -> {
			Tags requesterTags = Tags.of("source", "requester");
			Flux<Payload> flux = Flux.from(payloads).doOnNext(p -> {
				retain(p, rSockets);
				count(exchange, "payload", requesterTags);
			}).doOnError(t -> count(exchange, "error", requesterTags))
					.doFinally(s -> count(exchange, requesterTags)).publish()
					.refCount(rSockets.size());

			List<Flux<Payload>> payloadList = rSockets.stream().map(rSocket -> {
				if (rSocket instanceof ResponderRSocket) {
					ResponderRSocket socket = (ResponderRSocket) rSocket;
					return socket.requestChannel(payload, flux);
				}
				else {
					return rSocket.requestChannel(flux);
				}
			}).collect(Collectors.toList());

			return Flux.merge(payloadList).log(
					GatewayRSocket.class.getName() + ".request-channel", Level.FINEST);
		}).doOnNext(s -> count(exchange, "payload", responderTags))
				.doOnError(t -> count(exchange, "error", responderTags))
				.doFinally(s -> count(exchange, responderTags));
	}

	@Override
	public Mono<Payload> requestResponse(Payload payload) {
		AtomicReference<Timer.Sample> timer = new AtomicReference<>();
		GatewayExchange exchange = createExchange(REQUEST_RESPONSE, payload);
		return findRSocketOrCreatePending(exchange).flatMap(rSockets -> {
			retain(payload, rSockets);
			List<Mono<Payload>> results = rSockets.stream()
					.map(rSocket -> rSocket.requestResponse(payload))
					.collect(Collectors.toList());

			return Flux.merge(results).next();
			// TODO: does this cancel the others?
		}).doOnSubscribe(s -> timer.set(Timer.start(meterRegistry)))
				.doOnError(t -> count(exchange, "error"))
				.doFinally(s -> timer.get().stop(meterRegistry
						.timer(getMetricName(exchange), exchange.getTags())));
	}

	@Override
	public Flux<Payload> requestStream(Payload payload) {
		GatewayExchange exchange = createExchange(REQUEST_STREAM, payload);
		return findRSocketOrCreatePending(exchange).flatMapMany(rSockets -> {
			retain(payload, rSockets);
			List<Flux<Payload>> results = rSockets.stream()
					.map(rSocket -> rSocket.requestStream(payload))
					.collect(Collectors.toList());
			return Flux.merge(results);
		})
				// S N E F
				.doOnNext(s -> count(exchange, "payload"))
				.doOnError(t -> count(exchange, "error"))
				.doFinally(s -> count(exchange, Tags.empty()));
	}

	private void retain(Payload payload, List<RSocket> rSockets) {
		if (rSockets.size() > 1) {
			payload.retain(rSockets.size() - 1);
		}
	}

	/**
	 * First locate Route. If found, put route in exchange and execute filter chain. If
	 * successful, locate target RSocket. If not found, create a pending RSocket.
	 * @param exchange GatewayExchange.
	 * @return Target RSocket or empty.
	 */
	private Mono<List<RSocket>> findRSocketOrCreatePending(GatewayExchange exchange) {
		return this.routes.findRoute(exchange)
				.log(GatewayRSocket.class.getName() + ".find route", Level.FINEST)
				.flatMap(route -> {
					// put route in exchange for later use
					exchange.getAttributes().put(ROUTE_ATTR, route);
					return findRSocketOrCreatePending(exchange, route);
				}).switchIfEmpty(Mono.defer(() -> createPending(exchange)));

		// TODO: deal with connecting to cluster?
	}

	private Mono<List<RSocket>> findRSocketOrCreatePending(GatewayExchange exchange,
			Route route) {
		return executeFilterChain(route.getFilters(), exchange)
				.log(GatewayRSocket.class.getName() + ".after filter chain", Level.FINEST)
				.flatMapMany(success -> {
					Map<Key, String> tags = exchange.getRoutingMetadata().getTags();
					// TODO: use frame flag
					if (tags.containsKey(new Key("multicast"))) {
						List<Tuple2<String, RSocket>> rsockets = loadBalancerFactory
								.find(exchange.getRoutingMetadata());
						return Flux.fromIterable(rsockets);
					}
					return loadBalancerFactory.choose(exchange.getRoutingMetadata())
							.flatMapMany(
									tuple -> Flux.just((Tuple2<String, RSocket>) tuple));
				}).map(tuple -> {
					// TODO: this is routeId, should it be service name?
					// Tags tags = exchange.getTags().and("responder.id", tuple.getT1());
					// exchange.setTags(tags);
					return tuple.getT2();
				}).cast(RSocket.class).map(rSocket -> {
					if (log.isDebugEnabled()) {
						log.debug("Found RSocket: " + rSocket);
					}
					return rSocket;
				}).collectList()
				.log(GatewayRSocket.class.getName() + ".find rsocket", Level.FINEST);
	}

	protected Mono<List<RSocket>> createPending(GatewayExchange exchange) {
		if (log.isDebugEnabled()) {
			log.debug("Unable to find destination RSocket for "
					+ exchange.getRoutingMetadata());
		}
		// if a route can't be found or registered RSocket, create pending
		return pendingFactory.create(exchange).cast(RSocket.class)
				.map(Collections::singletonList);
	}

}
