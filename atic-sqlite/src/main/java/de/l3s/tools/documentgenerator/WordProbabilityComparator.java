package de.l3s.tools.documentgenerator;

import java.util.Comparator;

public class WordProbabilityComparator implements Comparator<WordProbability> {

	public int compare(WordProbability a, WordProbability b) {
		if(a.probability() < b.probability()) {
			return -1;
		} else if(a.probability() > b.probability()) {
			return 1;
		} else {
			return 0;
		}
	}

}
