package kafka.examples.consumer;

import static net.sourceforge.argparse4j.impl.Arguments.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple consumer which reads data from the user-provided
 * topic partitions. Here no load re-balance / heart-beat performed.
 * 
 * See {@link Consumer} for auto load balancing 
 * @author kamal
 */
public class SimpleConsumer implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SimpleConsumer.class);

	private String clientId;
	private KafkaConsumer<byte[], byte[]> consumer;
	private List<TopicPartition> partitions;
	
	private AtomicBoolean closed = new AtomicBoolean();
	private CountDownLatch shutdownlatch = new CountDownLatch(1);
	
	public SimpleConsumer(Properties config, List<TopicPartition> partitions) {
		this.clientId = config.getProperty(ConsumerConfig.CLIENT_ID_CONFIG);
		this.partitions = partitions;
		this.consumer = new KafkaConsumer<>(config);
	}
	
	@Override
	public void run() {

		try {
			consumer.assign(partitions);
			logger.info("C : {}, Started to process records for partitions : {}", clientId, partitions);
			
			while(!closed.get()) {
			
				ConsumerRecords<byte[], byte[]> records = consumer.poll(1000);
				
				if(records.isEmpty()) {
					logger.info("C : {}, Found no records. Sleeping for a while", clientId);
					sleep(500);
					continue;
				}
				
				for (ConsumerRecord<byte[], byte[]> record : records) {
					logger.info("C : {}, Record received topic : {}, partition : {}, key : {}, value : {}, offset : {}", 
							clientId, record.topic(), record.partition(), deserialize(record.key()), deserialize(record.value()),
							record.offset());
					sleep(100);
				}
			}
		} catch (Exception e) {
			logger.error("Error while consuming messages", e);
		} finally {
			consumer.close();
			shutdownlatch.countDown();
		}
	}
	
	public void close() {
		try {
			closed.set(true);
			shutdownlatch.await();
		} catch (InterruptedException e) {
			logger.error("Error", e);
		}
		logger.info("C : {}, consumer exited", clientId);
	}
	
	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			logger.error("Error", e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private <V> V deserialize(byte[] objectData) {
		return (V) SerializationUtils.deserialize(objectData);
	}
	
	public static void main(String[] args) {
		
		ArgumentParser parser = argParser();
		SimpleConsumer consumer = null;
		
		try {
			Namespace result = parser.parseArgs(args);
			
			Properties configs = new Properties();
			configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, result.getString("bootstrap.servers"));
			configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, result.getString("auto.offset.reset"));
			configs.put(ConsumerConfig.CLIENT_ID_CONFIG, result.getString("clientId"));
			configs.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, result.getString("max.partition.fetch.bytes"));
			configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
			configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
			
			List<TopicPartition> partitions = getPartitions(result.getString("topic.partitions"));
			consumer = new SimpleConsumer(configs, partitions);
			consumer.run();
			
		} catch (ArgumentParserException e) {
			if(args.length == 0)
				parser.printHelp();
			else 
				parser.handleError(e);
			System.exit(0);
		} finally {
			if(consumer != null)
				consumer.close();
		}
	}
	
	private static List<TopicPartition> getPartitions(String input) {

		List<TopicPartition> partitions = new ArrayList<>();
		String[] tps = input.split(",");
		
		for (String tp : tps) {
			String[] topicAndPartition = tp.split(":");
			partitions.add(new TopicPartition(topicAndPartition[0], Integer.valueOf(topicAndPartition[1])));
		}
		return partitions;
	}
	
	/**
     * Get the command-line argument parser.
     */
    private static ArgumentParser argParser() {
        ArgumentParser parser = ArgumentParsers
                .newArgumentParser("simple-consumer")
                .defaultHelp(true)
                .description("This example is to demonstrate kafka consumer capabilities");

        parser.addArgument("--bootstrap.servers").action(store())
                .required(true)
                .type(String.class)
                .help("comma separated broker list");

        parser.addArgument("--topic.partitions").action(store())
                .required(true)
                .type(String.class)
                .help("consume messages from partitions. Comma separated list e.g. t1:0,t1:1,t2:0");

        parser.addArgument("--clientId").action(store())
        		.required(false)
        		.setDefault("my-consumer")
        		.type(String.class)
        		.help("client identifier");
        
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
