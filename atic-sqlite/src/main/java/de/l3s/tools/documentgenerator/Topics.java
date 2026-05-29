package de.l3s.tools.documentgenerator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Topics implements Iterable<Topic> {
	
	private final List<Topic> topics;
//	private final List<TopicCorrelation> correlations;

	public Topics(Reader topics, Reader correlations) throws IOException {
		this.topics = readTopics(topics);
		readCorrelations(correlations);
	}

	public int size() {
		return this.topics.size();
	}
	
	public Topic get(int id) {
		if((id < 0) || (id >= this.topics.size()))
			return null;
		
		return this.topics.get(id);
	}
	
	public int indexOf(Topic topic) {
		return this.topics.indexOf(topic);
	}
	
//	public TopicCorrelation getCorrelations(Topic topic) {
//		if(topic == null)
//			return null;
//		
//		int id = this.topics.indexOf(topic);
//		if(id < 0)
//			return null;
//		
//		return correlations.get(id);
//	}
	
	private List<Topic> readTopics(Reader reader) throws IOException {
		List<Topic> topics = new ArrayList<Topic>();

		// process topics line by line
		String line;
		BufferedReader buf = new BufferedReader(reader);
		while((line = buf.readLine()) != null) {
			// ignore comment lines
			if(line.startsWith("#"))
				continue;
			
			// extract word probabilities of each topic
			List<WordProbability> words = new ArrayList<WordProbability>();
			String[] fields = line.split("\\s");
			boolean first = true;
			for(String field : fields) {
				// extract word and probability
				String[] data = field.split(":");
				if(data.length != 2)
					continue;
				
				// the first field is the topic name and the topic probability
				if(first) {
					first = false;
					continue;
				}

				// store word probability
				try {
					String word = data[0];
					double prop = Double.parseDouble(data[1]);
					words.add(new WordProbability(word, prop));
				} catch (NumberFormatException e) {
					System.err.println("could not parse '" + data[1] + "' as a double! Ignored");
				}
			}
			topics.add(new Topic(words));
		}
		reader.close();
		
		return topics;
	}
	
	private void readCorrelations(Reader reader) throws IOException {
		if(reader == null)
			return;
		
		if(this.topics == null) {
			System.err.println("no topics loaded, cannot load topic correltations");
			return;
		}
		
		String line;
		int correlation = 0;
		BufferedReader buf = new BufferedReader(reader);
		while((line = buf.readLine()) != null) {
			// ignore comment lines
			if(line.startsWith("#"))
				continue;
			
			String[] fields = line.split("\\s");
			Map<Topic, Double> map = new HashMap<Topic, Double>();
			boolean first = true;
			for(int i=-1; i<fields.length-1; i++) {
				// we can stop this loop when we exceed the number of topic 
				if(i >= this.topics.size())
					break;
				
				// the first field is the topic name
				if(first) {
					first = false;
					continue;
				}
				
				// we ignore the correlation of a topic with itself
				if(i == correlation)
					continue;
				
				try {
					// get the corresponding topic
					Topic topic = this.topics.get(i);
					
					// get the correlation probability
					double prop = Double.parseDouble(fields[i+1]);

					// store the correlation, if it is significantly large
					if(prop >= TopicCorrelation.MIN_CORRELATION)
						map.put(topic, prop);
				} catch (NumberFormatException e) {
					System.err.println("could not parse '" + fields[i+1] + "' as a double! Ignored");
				}
			}
			
			// store this topics correlations
			Topic topic = this.topics.get(correlation);
			topic.setCorrelation(new TopicCorrelation(map));
			correlation++;
		}
		reader.close();
	}
	
	public Iterator<Topic> iterator() {
		return this.topics.iterator();
	}
	
	public String toString() {
		StringBuilder string = new StringBuilder();
		for(Topic topic : this.topics) {
			string.append(topic.toString());
			string.append("\n");
		}
		return string.toString();
	}
	
}
