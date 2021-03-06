/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.io.net.tcp;

import org.apache.commons.collections.list.SynchronizedList;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import reactor.Environment;
import reactor.core.processor.RingBufferProcessor;
import reactor.core.processor.RingBufferWorkProcessor;
import reactor.core.support.StringUtils;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.io.buffer.Buffer;
import reactor.io.codec.Codec;
import reactor.io.codec.StringCodec;
import reactor.io.net.NetStreams;
import reactor.io.net.Spec;
import reactor.rx.Promise;
import reactor.rx.Stream;
import reactor.rx.Streams;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author Stephane Maldini
 */
@Ignore
public class SmokeTests {
	private Processor<String, String>                      processor;
	private reactor.io.net.http.HttpServer<String, String> httpServer;

	private final AtomicInteger postReduce         = new AtomicInteger();
	private final AtomicInteger windows            = new AtomicInteger();
	private final AtomicInteger integer            = new AtomicInteger();
	private final AtomicInteger integerPostTimeout = new AtomicInteger();
	private final AtomicInteger integerPostTake    = new AtomicInteger();
	private final AtomicInteger integerPostConcat  = new AtomicInteger();

	private final int count   = 33_000;
	private final int threads = 6;
	private final int iter    = 10;
	private final int windowBatch    = 1000;
	private final int takeCount    = 1;

	@SuppressWarnings("unchecked")
	private List<Integer> windowsData = SynchronizedList.decorate(new ArrayList<>());

	@Test
	public void testMultipleConsumersMultipleTimes() throws Exception {
		int fulltotalints = 0;

		for (int t = 0; t < iter; t++) {
			List<Integer> clientDatas = new ArrayList<>();
			try {
				clientDatas.addAll(getClientDatas(threads, new Sender(), count));
				Collections.sort(clientDatas);

				fulltotalints += clientDatas.size();

				System.out.println(clientDatas.size() + "/" + (integerPostConcat.get() * windowBatch));

				for (int i = 0; i < clientDatas.size(); i++) {
					if (i > 0) {
						assertThat(clientDatas.get(i - 1), is(clientDatas.get(i) - 1));
					}
				}
				assertThat(clientDatas.size(), greaterThanOrEqualTo(count));

			} catch (Throwable ae) {
				System.out.println(clientDatas.size() + " - " + clientDatas);
				Collections.sort(windowsData);
				System.out.println(windowsData.size() + " - " + windowsData);
				List<Integer> dups = findDuplicates(windowsData);
				Collections.sort(dups);
				System.out.println("Dups: "+dups.size()+" - " + dups);
				throw ae;
			} finally {
				printStats(t);
			}
		}

		// check full totals because we know what this should be

		assertThat(fulltotalints, is(count * iter));
	}

	@Before
	public void loadEnv() throws Exception {
		Environment.initializeIfEmpty().assignErrorJournal();
		setupFakeProtocolListener();
	}

	@After
	public void clean() throws Exception {
		httpServer.shutdown().awaitSuccess();
	}

	public List<Integer> findDuplicates(List<Integer> listContainingDuplicates) {
		final List<Integer> setToReturn = new ArrayList<>();
		final Set<Integer> set1 = new HashSet<>();

		for (Integer yourInt : listContainingDuplicates) {
			if (!set1.add(yourInt)) {
				setToReturn.add(yourInt);
			}
		}
		return setToReturn;
	}

	private void setupFakeProtocolListener() throws Exception {
		processor = RingBufferProcessor.create(false);
		Stream<String> bufferStream = Streams
				.wrap(processor)
						//.log("test")
				.window(windowBatch, 2, TimeUnit.SECONDS)
				.flatMap(s -> s
						.observe(d ->
										windows.getAndIncrement()
						)
						.reduce("", String::concat)
						.observe(d ->
										postReduce.getAndIncrement()
						))
				.process(RingBufferWorkProcessor.create(false));

//		Stream<Buffer> bufferStream = Streams
//				.wrap(processor)
//				.window(100, 1, TimeUnit.SECONDS)
//				.flatMap(s -> s.dispatchOn(Environment.sharedDispatcher()).reduce(new Buffer(), (prev, next) -> {
//					return prev.append(next);
//				}))
//				.process(RingBufferWorkProcessor.create(false));

		httpServer = NetStreams.httpServer(server -> server
						.codec(new StringCodec()).listen(0)
		);


		httpServer.get("/data", (request) -> {
			request.responseHeaders().removeTransferEncodingChunked();
			request.addResponseHeader("Content-type", "text/plain");
			request.addResponseHeader("Expires", "0");
			request.addResponseHeader("X-GPFDIST-VERSION", "Spring XD");
			request.addResponseHeader("X-GP-PROTO", "1");
			request.addResponseHeader("Cache-Control", "no-cache");
			request.addResponseHeader("Connection", "close");
			return bufferStream
					.observe(d ->
									integer.getAndIncrement()
					)


					.take(takeCount)
					.observe(d ->
									integerPostTake.getAndIncrement()
					)
					.timeout(2, TimeUnit.SECONDS, Streams.<String>empty())
					.observe(d ->
									integerPostTimeout.getAndIncrement()
					)
							//.concatWith(Streams.just(new Buffer().append("END".getBytes(Charset.forName("UTF-8")))))

					.concatWith(Streams.just("END"))
					.observe(d ->
									windowsData.addAll(parseCollection(d))
					)
					.observe(d ->
									integerPostConcat.getAndIncrement()
					)
					.observeComplete(no -> {
								integerPostConcat.decrementAndGet();
								System.out.println("YYYYY COMPLETE " + Thread.currentThread());
							}
					);
		});

		httpServer.start().awaitSuccess();
	}

	private List<String> getClientDataPromise() throws Exception {
		reactor.io.net.http.HttpClient<String, String> httpClient = NetStreams.httpClient(new Function<Spec.HttpClientSpec<String, String>, Spec.HttpClientSpec<String, String>>() {

			@Override
			public Spec.HttpClientSpec<String, String> apply(Spec.HttpClientSpec<String, String> t) {
				return t.codec(new StringCodec()).connect("localhost", httpServer.getListenAddress().getPort())
						.dispatcher(Environment.sharedDispatcher());
			}
		});
		Promise<List<String>> content = httpClient
				.get("/data", t -> Streams.empty())
				.flatMap(Stream::toList);

		httpClient.open().awaitSuccess();
		content.awaitSuccess(20, TimeUnit.SECONDS);
		httpClient.close().awaitSuccess();
		return content.get();
	}

	@SuppressWarnings("unchecked")
	private List<Integer> getClientDatas(int threadCount, final Sender sender, int count) throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final List<Integer> datas = SynchronizedList.decorate(new ArrayList<>());

		windowsData.clear();
		Runnable srunner = new Runnable() {
			public void run() {
				try {
					sender.sendNext(count);
				} catch (Exception ie) {
					ie.printStackTrace();
				}
			}
		};
		Thread st = new Thread(srunner, "SenderThread");
		st.start();
		CountDownLatch thread = new CountDownLatch(threadCount);
		AtomicInteger counter = new AtomicInteger();
		for (int i = 0; i < threadCount; ++i) {
			Runnable runner = new Runnable() {
				public void run() {
					try {
						boolean empty = false;
						while (true) {
							List<String> res = getClientDataPromise();
							if (res == null) {
								if (empty) break;
								empty = true;
								continue;
							}

							List<Integer> collected = parseCollection(res);
							int size = collected.size();

							//previous empty
							if (size == 0 && empty) break;

							datas.addAll(collected);
							counter.addAndGet(size);
							empty = size == 0;
							System.out.println("Client received " + size + " elements, current total: " + counter + ", batches: " +
									integerPostConcat);
						}
						System.out.println("Client finished");
					} catch (Exception ie) {
						ie.printStackTrace();
					} finally {
						thread.countDown();
					}
				}
			};
			Thread t = new Thread(runner, "SmokeThread" + i);
			t.start();
		}
		latch.countDown();

		thread.await(60, TimeUnit.SECONDS);
		return datas;
	}

	private List<Integer> parseCollection(List<String> res) {
		StringBuilder buf = new StringBuilder();
		for (int j = 0; j < res.size(); j++) {
			buf.append(res.get(j));
		}
		return parseCollection(buf.toString());
	}

	private List<Integer> parseCollection(String res) {
		List<Integer> integers = new ArrayList<>();

		//System.out.println(Thread.currentThread()+res.replaceAll("\n",","));
		List<String> split = split(res);
		for (String d : split) {
			if (StringUtils.hasText(d) && !d.contains("END")) {
				integers.add(Integer.parseInt(d));
			}
		}
		return integers;
	}

	private static List<String> split(String data) {
		return Arrays.asList(data.split("\\r?\\n"));
	}

	class Sender {
		int x = 0;

		void sendNext(int count) {
			for (int i = 0; i < count; i++) {
//				System.out.println("XXXX " + x);
				String data = x++ + "\n";
				processor.onNext(data);
			}
		}
	}

	private void printStats(int t){
		System.out.println("\n" +
				"---- STATISTICS ----------------- \n" +
				"run: " + (t + 1) + " \n" +
				"windowed : " + windows + " \n" +
				"post reduce: " + postReduce + " \n" +
				"client batches: " + integer + " \n" +
				"post take batches: " + integerPostTake + "\n" +
				"post timeout batches: " + integerPostTimeout + "\n" +
				"post concat batches: " + integerPostConcat + "\n" +
				"-----------------------------------");

	}

	public class DummyCodec extends Codec<Buffer, Buffer, Buffer> {

		@SuppressWarnings("resource")
		@Override
		public Buffer apply(Buffer t) {
			Buffer b = t.flip();
			if (Thread.currentThread().getName().contains("reactor-tcp")) {
				for (StackTraceElement se : Thread.currentThread().getStackTrace()) {
					System.out.println(Thread.currentThread() + "- " + se.getLineNumber() + ": " + se);
				}
				System.out.println(Thread.currentThread() + " END\n");
			}
			//System.out.println("XXXXXX " + Thread.currentThread()+" "+b.asString().replaceAll("\n", ", "));
			return b;
		}

		@Override
		public Function<Buffer, Buffer> decoder(Consumer<Buffer> next) {
			return null;
		}

	}
}
