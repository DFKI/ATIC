package de.l3s.tools.namegenerator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NameGenerator {
	public static enum Gender { MALE, FEMALE };
	
	protected class Name {
		private final double cumulativeProb;
		private final String name;
		protected Name(double cumulativeProb, String name) {
			this.cumulativeProb = cumulativeProb;
			this.name = name;
		}
		public String toString() {
			return cumulativeProb + ":" + name;
		}
	}

	private Random random = new Random(0);
	private Random randomizeNames = new Random(0);
	
	private boolean randomizedNames = false;;
	
	private Name[] surnames;
	private Name[] maleFirstnames;
	private Name[] femaleFirstnames;
	
	public NameGenerator() {
	}
	
	public void setSeed(long seed) {
		this.random = new Random(seed);
	}
	
	public void setRandomizedNames(boolean bool) {
		this.randomizedNames = bool;
	}
	
	public void setSurnames(String filename) {
		setSurnames(new File(filename));
	}
	
	public void setSurnames(File file) {
		try {
			setSurnames(new FileInputStream(file));
		} catch (FileNotFoundException e) {
			System.err.println("Could not find surname file '" + file.getAbsolutePath() + "'!");
			e.printStackTrace();
		}
	}
	
	public void setSurnames(InputStream in) {
		List<Name> list = getNameList(in);
		surnames = list.toArray(new Name[0]);
	}
	
	public void setFirstnames(Gender gender, String filename) {
		setFirstnames(gender, new File(filename));
	}

	public void setFirstnames(Gender gender, File file) {
		try {
			setFirstnames(gender, new FileInputStream(file));
		} catch (FileNotFoundException e) {
			System.err.println("Could not find first name file '" + file.getAbsolutePath() + "'!");
			e.printStackTrace();
		}
	}
	
	public void setFirstnames(Gender gender, InputStream in) {
		List<Name> list = getNameList(in);
		if(gender == Gender.MALE) {
			maleFirstnames = list.toArray(new Name[0]);
		} else {
			femaleFirstnames = list.toArray(new Name[0]);
		}
	}
	
	private Name[] getFirstnames(Gender gender) {
		Name[] names = null;
		if(gender == Gender.MALE) {
			if(this.maleFirstnames == null) {
				setFirstnames(Gender.MALE, this.getClass().getClassLoader().getResourceAsStream("de/l3s/tools/namegenerator/dist.male.first"));
			}
			names = this.maleFirstnames;
		} else {
			if(this.femaleFirstnames == null) {
				setFirstnames(Gender.FEMALE, this.getClass().getClassLoader().getResourceAsStream("de/l3s/tools/namegenerator/dist.female.first"));
			}
			names = this.femaleFirstnames;
		}
		
		return names;
	}
	
	private Name[] getSurnames() {
		if(this.surnames == null) {
			setSurnames(this.getClass().getClassLoader().getResourceAsStream("de/l3s/tools/namegenerator/dist.all.last"));
		}
		return this.surnames;
	}
	
	private List<Name> getNameList(InputStream in) {
		List<Name> list = new ArrayList<Name>();
		
		try {
			String line;
			double cumulativeProb = 0.0;
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			while((line = reader.readLine()) != null) {
				// split the line into fields, separated by multiple white spaces
				String[] fields = line.split("\\s+");
				if(fields.length < 2) {
					System.err.println("Could not split this line into at least two fields:\n\t" + line);
					continue;
				}
				
				// extract the name and make the first character upper case and the rest lower case
				String name = fields[0];
				name = name.substring(0, 1).toUpperCase().concat(name.substring(1).toLowerCase());
				
				if(this.randomizedNames) {
					name = randomizeName(name);
				}

				// extract the probability and count up the cumulative probability
				double prob = 0.0;
				try {
					prob = Double.parseDouble(fields[1]);
					if(prob <= 0.0)
						continue;
					
					cumulativeProb += prob;
				} catch (NumberFormatException e) {
					System.err.println("Could not parse '" + fields[1] + "' into a double!");
					e.printStackTrace();
					continue;
				}

				// add the new name to the list
				list.add(new Name(cumulativeProb, name));
			}
			reader.close();
		} catch (IOException e) {
			System.err.println("Could not read from / close name file!");
			e.printStackTrace();
		}
		
		return list;
	}
	
	public String nextFirstname() {
		Gender gender = Gender.MALE;
		if(random.nextBoolean()) {
			gender = Gender.FEMALE;
		}

		return nextFirstname(gender);
	}
	
	public String nextFirstname(Gender gender) {
		Name[] firstnames = getFirstnames(gender);
		String firstname = nextName(firstnames);
		
		return firstname;
	}
	
	public String nextSurname() {
		Name[] surnames = getSurnames();
		String surname = nextName(surnames);

		return surname;
	}
	
	public String nextName() {
		Gender gender = Gender.MALE;
		if(random.nextBoolean()) {
			gender = Gender.FEMALE;
		}
		
		return nextName(gender);
	}
	
	public String nextName(Gender gender) {
		String firstname = nextFirstname(gender);
		String surname = nextSurname();
		String fullname = firstname + " " + surname;
		
		return fullname;
	}
	
	private String nextName(Name[] names) {
		if(names.length == 0)
			return null;
		
		double rand = this.random.nextDouble() * names[names.length - 1].cumulativeProb;
		return getName(names, rand);
	}
	
	// gets the name with the smalles cumulativeProb of those having cumulativeProb >= prob
	// this is done using binary search
	protected String getName(Name[] names, double prob) {
		int start = 0;
		int end = names.length - 1;
		
		while(start <= end) {
			int mid = (start + end) >> 1;
		
			double midProb = names[mid].cumulativeProb;
			
			if(midProb >= prob) {
				if(start == end) {
					start++;
				} else {
					end = mid;
				}
			} else {
				start = mid + 1;
			}
		}
		
		// if we found the last name but its prob is still too low, we have to return null
		if((end == names.length-1) && (prob > names[end].cumulativeProb))
			return null;

		// return the found name
		return names[end].name;
	}
	
	private String randomizeName(String name) {
		char[] chars = new char[] { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k',
				'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z' };

		StringBuilder string = new StringBuilder();
		for(int i=0; i<name.length(); i++) {
			char c = chars[this.randomizeNames.nextInt(chars.length)];
			
			// the first char is uppercase
			if(i==0)
				c = String.valueOf(c).toUpperCase().charAt(0);
			
			string.append(c);
		}
		
		return string.toString();
	}
	
}
