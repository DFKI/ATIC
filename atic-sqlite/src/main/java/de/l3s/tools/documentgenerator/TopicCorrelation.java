package de.l3s.tools.documentgenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TopicCorrelation {
	
	public final static double MIN_CORRELATION = 0.001;

	private final List<Topic> topics;
	private final List<Double> correlations;

	public TopicCorrelation(Map<Topic, Double> correlations) {
		List<Map.Entry<Topic, Double>> entries = new ArrayList<Map.Entry<Topic, Double>>(correlations.entrySet());
		Collections.sort(entries, Collections.reverseOrder(new MapEntryDoubleValueComparator<Topic>()));
		
		this.topics = new ArrayList<Topic>();
		this.correlations = new ArrayList<Double>();
		
		double n = normalizeFactor(correlations.values());
		
		for(Map.Entry<Topic, Double> entry : entries) {
			this.topics.add(entry.getKey());
			this.correlations.add(entry.getValue() / n);
		}
	}
	
	public List<Topic> topics() {
		return this.topics;
	}
	
	public List<Double> probabilities() {
		return this.correlations;
	}
	
	public Topic get(double random) {
		if(topics.size() == 0)
			return null;

		// set up loop
		double prob = this.correlations.get(0);
		int id = 0;
		while((id < this.correlations.size()-1) && (prob < random)) {
			id++;
			prob += this.correlations.get(id);
		}
		
		return this.topics.get(id);
	}
	
	private double normalizeFactor(Collection<Double> coll) {
		double sum = 0.0;
		for(Double d : coll) {
			sum += d;
		}
		return sum;
	}

}
