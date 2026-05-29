package de.l3s.tools.documentgenerator;

public class WordProbability {
	
	private final String word;
	private final double probability;
	
	public WordProbability(String word, double probability) {
		this.word = word;
		this.probability = probability;
	}
	
	public String word() {
		return this.word;
	}

	public double probability() {
		return this.probability;
	}
	
	public String toString() {
		return this.word + ":" + this.probability;
	}
	
}
