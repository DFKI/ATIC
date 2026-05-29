package de.l3s.tools.documentgenerator;

import java.util.Comparator;
import java.util.Map;

public class MapEntryDoubleValueComparator<T> implements Comparator<Map.Entry<T, Double>>{
	public int compare(Map.Entry<T, Double> a, Map.Entry<T, Double> b) {
		return a.getValue().compareTo(b.getValue());
	}
}
