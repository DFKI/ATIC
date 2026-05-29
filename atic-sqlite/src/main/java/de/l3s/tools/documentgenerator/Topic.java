package de.l3s.tools.documentgenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class Topic implements Iterable<WordProbability> {
	
	private final List<WordProbability> words;
	private TopicCorrelation correlation;
		
	public Topic(List<WordProbability> words) {
		// get the normalization factor of the words
		double normalizeFactor = computeNormalizeFactor(words);

		// and normalize the words
		List<WordProbability> normalizedWords = new ArrayList<WordProbability>(words.size());
		for(WordProbability wp : words) {
			normalizedWords.add(new WordProbability(wp.word(), wp.probability() / normalizeFactor));
		}

		// sort the normalized word probabilities in reverse order, w.r.t. their probabilities
		Collections.sort(normalizedWords, Collections.reverseOrder(new WordProbabilityComparator()));

		this.words = normalizedWords;
	}
	
	public void setCorrelation(TopicCorrelation correlation) {
		this.correlation = correlation;
	}
	
	public Topic getCorrelatedTopic(double rand) {
		return this.correlation.get(rand);
	}
	
	public TopicCorrelation getCorrelatedTopics() {
		return this.correlation;
	}
	
	protected static double computeNormalizeFactor(List<WordProbability> words) {
		return computeNormalizeFactor(words.iterator());
	}
	
	protected static double computeNormalizeFactor(Iterator<WordProbability> words) {
		double sum = 0.0f;
		while(words.hasNext()) {
			WordProbability wp = words.next();
			sum += wp.probability();
		}
		return sum;
	}
	
	public String nextWord(Random rand) {
		if(words.isEmpty())
			return null;

		// set up loop
		Iterator<WordProbability> it = this.words.iterator();
		WordProbability wp = it.next();
		double target = rand.nextDouble();
		double prop = wp.probability();
		
		// iterate until our accumulative probability exceeds the random number
		while(it.hasNext() && (target >= prop)) {
			wp = it.next();
			prop += wp.probability();
		}
		
		return wp.word();
	}
	
	public Iterator<WordProbability> iterator() {
		return this.words.iterator();
	}
	
	public String toString() {
		StringBuilder string = new StringBuilder();
		for(WordProbability wp : this.words) {
			string.append(wp.toString());
			string.append(" ");
		}
		return string.toString();
	}

}
