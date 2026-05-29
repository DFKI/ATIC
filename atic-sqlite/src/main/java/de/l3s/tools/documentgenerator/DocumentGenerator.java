package de.l3s.tools.documentgenerator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Random;

public class DocumentGenerator {

	private final Topics topics;
	private final Random rand;
	
	public DocumentGenerator() throws IOException {
        ///de/l3s/tools/documentgenerator
        
        //getClass().getClassLoader().getResourceAsStream("/de/l3s/tools/documentgenerator/topics-nips.dat");
        
		this(new InputStreamReader(DocumentGenerator.class.getResourceAsStream("/de/l3s/tools/documentgenerator/topics-nips.dat")),
		     new InputStreamReader(DocumentGenerator.class.getResourceAsStream("/de/l3s/tools/documentgenerator/topics-nips.dat.cor")));
	}
	
	public DocumentGenerator(Reader topics, Reader correlations) throws IOException {
		this.topics = new Topics(topics, correlations);
		this.rand = new Random(0);
	}
	
	public String nextDocument(int size, Topic ...topics) {
		int[] topicIDs = new int[topics.length];
		for(int i=0; i<topics.length; i++) {
			topicIDs[i] = this.topics.indexOf(topics[i]);
		}
		return nextDocument(size, topicIDs);
	}
	
	public String nextDocument(int size, int ...topics) {
		StringBuilder string = new StringBuilder();
		
		// generate 'size' words
		for(int i=0; i<size; i++) {
			// uniform-randomly pick a topic
			int topicNo = this.rand.nextInt(topics.length);
			int topicID = topics[topicNo];
			Topic topic = this.topics.get(topicID);
			
			// get a new word from that topic
			String word = topic.nextWord(this.rand);
			
			// add the word to the document
			string.append(word);
			
			// add a white space if more words are to come
			if(size-i > 1)
				string.append(" ");
		}
		
		return string.toString();
	}
	
	public Topic randomTopic() {
		return this.topics.get(this.rand.nextInt(this.topics.size()));
	}
	
}
