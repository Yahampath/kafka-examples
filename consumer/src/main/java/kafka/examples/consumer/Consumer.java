package kafka.examples.consumer;

import static net.sourceforge.argparse4j.impl.Arguments.store;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Consumer<K extends Serializable, V extends Serializable> implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(Consumer.class);
	
	private KafkaConsumer<K, V> consumer;
	private final String clientId;
	private List<String> topics;
	
	private AtomicBoolean closed = new AtomicBoolean();
	private CountDownLatch shutdownLatch = new CountDownLatch(1);
	
	public Consumer(Properties configs, String clientId, List<String> topics) {

		this.clientId = clientId;
		this.topics = topics;
		configs.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
		this.consumer = new KafkaConsumer<>(configs, new CustomDeserializer<K>(), new CustomDeserializer<V>());
	}
	
	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			logger.error("C : {}, Error", e);
		}
	}
	
	@Override
	public void run() {
	
		logger.info("Starting consumer : {}", clientId);

		ExecutorService executor = Executors.newSingleThreadExecutor(new CustomFactory(clientId + "_Processor"));
		final Map<TopicPartition, Long> partitionToUncommittedOffsetMap = new ConcurrentHashMap<>();
		final List<Future<Boolean>> futures = new ArrayList<>();
		
		ConsumerRebalanceListener listener = new ConsumerRebalanceListener() {

			@Override
			public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
				if(!futures.isEmpty())
					futures.get(0).cancel(true);
				
				logger.info("C : {}, Revoked topicPartitions : {}", clientId, partitions);
				commitOffsets(partitionToUncommittedOffsetMap);
			}

			@Override
			public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
				for (TopicPartition tp : partitions) {
					OffsetAndMetadata offsetAndMetaData = consumer.committed(tp);
					long startOffset = offsetAndMetaData != null ? offsetAndMetaData.offset() : -1L;
					logger.info("C : {}, Assigned topicPartion : {} offset : {}", clientId, tp, startOffset);

					if(startOffset >= 0)
						consumer.seek(tp, startOffset);
				}
			}
		};
		
		consumer.subscribe(topics, listener);
		logger.info("Started to process records for consumer : {}", clientId);
		
		while(!closed.get()) {
			
			ConsumerRecords<K, V> records = consumer.poll(1000);
			
			if(records == null || records.isEmpty()) {
				logger.info("C: {}, Found no records, Sleeping for a while", clientId);
				sleep(500);
				continue;
			}
			
			/**
			 * After receiving the records, pause all the partitions and do heart-beat manually
			 * to avoid the consumer instance gets kicked-out from the group by the consumer coordinator
			 * due to the delay in the processing of messages
			 */
			consumer.pause(consumer.assignment().toArray(new TopicPartition[0]));
			Future<Boolean> future = executor.submit(new ConsumeRecords(records, partitionToUncommittedOffsetMap));
			futures.add(future);
			
			Boolean isCompleted = false;
			while(!isCompleted && !closed.get()) {
				try	{
					isCompleted = future.get(3, TimeUnit.SECONDS); // wait up-to heart-beat interval
				} catch (TimeoutException e) {
					logger.debug("C : {}, heartbeats the coordinator", clientId);
					consumer.poll(0); // does heart-beat
					commitOffsets(partitionToUncommittedOffsetMap); 
				} catch (CancellationException e) {
					logger.debug("C : {}, ConsumeRecords Job got cancelled", clientId);
					break;
				} catch (ExecutionException | InterruptedException e) {
					logger.error("C : {}, Error while consuming records", clientId, e);
					break;
				}
			}
			futures.remove(future);
			consumer.resume(consumer.assignment().toArray(new TopicPartition[0]));
			commitOffsets(partitionToUncommittedOffsetMap);
		}
		
		try {
			executor.shutdownNow();
			while(!executor.awaitTermination(5, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			logger.error("C : {}, Error while exiting the consumer", clientId, e);
		}
		consumer.close();
		shutdownLatch.countDown();
		logger.info("C : {}, consumer exited", clientId);
	}

	private void commitOffsets(Map<TopicPartition, Long> partitionToOffsetMap) {

		if(!partitionToOffsetMap.isEmpty()) {
			Map<TopicPartition, OffsetAndMetadata> partitionToMetadataMap = new HashMap<>();
			for(Entry<TopicPartition, Long> e : partitionToOffsetMap.entrySet()) {
				partitionToMetadataMap.put(e.getKey(), new OffsetAndMetadata(e.getValue() + 1));
			}
			
			logger.info("C : {}, committing the offsets : {}", clientId, partitionToMetadataMap);
			consumer.commitSync(partitionToMetadataMap);
			partitionToOffsetMap.clear();
		}
	}

	public void close() {
		try {
			closed.set(true);
			shutdownLatch.await();
		} catch (InterruptedException e) {
			logger.error("Error", e);
		}
	}
	
	private class ConsumeRecords implements Callable<Boolean> {
		
		ConsumerRecords<K, V> records;
		Map<TopicPartition, Long> partitionToUncommittedOffsetMap;
		
		public ConsumeRecords(ConsumerRecords<K, V> records, Map<TopicPartition, Long> partitionToUncommittedOffsetMap) {
			this.records = records;
			this.partitionToUncommittedOffsetMap = partitionToUncommittedOffsetMap;
		}
		
		@Override
		public Boolean call() {

			logger.info("C: {}, Number of records received : {}", clientId, records.count());
			try {
				for(ConsumerRecord<K, V> record : records) {
					TopicPartition tp = new TopicPartition(record.topic(), record.partition());
					logger.info("C : {}, Record received topicPartition : {} offset : {}", clientId, tp, record.offset());
					partitionToUncommittedOffsetMap.put(tp, record.offset());
					Thread.sleep(100); // Adds more processing time for a record
				}
			} catch (InterruptedException e) {
				logger.info("C : {}, Record consumption interrupted!", clientId);
			} catch (Exception e) {
				logger.error("Error while consuming", e);
			}
			return true;
		}
	}
	
	private static class CustomDeserializer<T extends Serializable> implements Deserializer<T> {
		
		@Override
		public void configure(Map<String, ?> configs, boolean isKey) {
		}

		@SuppressWarnings("unchecked")
		@Override
		public T deserialize(String topic, byte[] objectData) {
			return (objectData == null) ? null : (T) SerializationUtils.deserialize(objectData);
		}

		@Override
		public void close() {
		}
	}
	
	private static class CustomFactory implements ThreadFactory {

		private String threadPrefix;
		private int counter = 0;
		
		public CustomFactory(String threadPrefix) {
			this.threadPrefix = threadPrefix;
		}
		
		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, threadPrefix + "-" + counter++);
		}
	}
	
	public static void main(String[] args) throws InterruptedException {
		
		ArgumentParser parser = argParser();
		List<Consumer<String, Integer>> consumers = new ArrayList<>();
		
		try {
			Namespace result = parser.parseArgs(args);

			int numConsumer = result.getInt("numConsumer");
			List<String> topics = Arrays.asList(result.getString("topics").split(","));
			Properties configs = getConsumerConfigs(result);
			
			ExecutorService executor = Executors.newFixedThreadPool(numConsumer);

			// Start consumers one by one after 20 seconds
			for (int i=0; i<numConsumer; i++) {
				Consumer<String, Integer> consumer = new Consumer<String, Integer>(configs, "Worker" + i, topics);
				consumers.add(consumer);
				executor.submit(consumer);
				Thread.sleep(TimeUnit.SECONDS.toMillis(20));
			}
			
			Thread.sleep(TimeUnit.SECONDS.toMillis(60)); // let all the consumers run for a minute
			
			// Stop consumers one by one after 20 seconds
			for (Consumer<String, Integer> consumer : consumers) {
				Thread.sleep(TimeUnit.SECONDS.toMillis(20));
				consumer.close();
			}
			
			executor.shutdown();
			while(!executor.awaitTermination(5, TimeUnit.SECONDS));
			logger.info("Exiting the application");
			
		} catch (ArgumentParserException e) {
			if(args.length == 0)
				parser.printHelp();
			else 
				parser.handleError(e);
			System.exit(0);
		}
	}
	
	private static Properties getConsumerConfigs(Namespace result) {
		Properties configs = new Properties();
		configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, result.getString("bootstrap.servers"));
		configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, result.getString("auto.offset.reset"));
		configs.put(ConsumerConfig.GROUP_ID_CONFIG, result.getString("groupId"));
		configs.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, result.getString("max.partition.fetch.bytes"));
		return configs;
	}
	
	/**
     * Get the command-line argument parser.
     */
    private static ArgumentParser argParser() {
        ArgumentParser parser = ArgumentParsers
                .newArgumentParser("consumer-rebalancer")
                .defaultHelp(true)
                .description("This example demonstrates kafka consumer auto-rebalance capabilities");

        parser.addArgument("--bootstrap.servers").action(store())
                .required(true)
                .type(String.class)
                .help("comma separated broker list");

        parser.addArgument("--topics").action(store())
                .required(true)
                .type(String.class)
                .help("consume messages from topics. Comma separated list e.g. t1,t2");

        parser.addArgument("--groupId").action(store())
        		.required(true)
        		.type(String.class)
        		.help("Group identifier");
        
        parser.addArgument("--numConsumer").action(store())
        		.required(true)
        		.type(Integer.class)
        		.help("Number of consumer instances in the group");
        
        parser.addArgument("--auto.offset.reset").action(store())
        		.required(false)
        		.setDefault("earliest")
        		.type(String.class)
        		.choices("earliest", "latest")
        		.help("What to do when there is no initial offset in Kafka");
        
        parser.addArgument("--max.partition.fetch.bytes").action(store())
        		.required(false)
        		.setDefault("3000")
        		.type(String.class)
        		.help("The maximum amount of data per-partition the server will return");
        
        return parser;
    }
}


/**
 * $Log$
 *  
 */
