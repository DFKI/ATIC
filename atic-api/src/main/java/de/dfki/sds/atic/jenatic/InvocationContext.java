package de.dfki.sds.atic.jenatic;

import de.dfki.sds.atic.ac.User;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static java.util.stream.Collectors.toSet;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

public class InvocationContext {

    //used when no invocationcontext is given
    public static final InvocationContext EMPTY = new InvocationContext() {
        @Override
        public boolean isEmpty() {
            return true;
        }
    };

    public static final Symbol USER_ID = Symbol.create("atic.userId");
    public static final Symbol PRIMARY_GROUP_ID = Symbol.create("atic.primaryGroupId");
    public static final Symbol GROUP_IDS = Symbol.create("atic.groupIds");
    
    private Integer userId;
    private Integer primaryGroupId;
    private Set<Integer> groupIds;
    
    private InvocationContext() {
        
    }

    private InvocationContext(Builder builder) {
        
        if(builder.user != null) {
            this.userId = builder.user.getId();
            this.primaryGroupId = builder.user.getPrimaryGroup().getId();
            this.groupIds = Collections.unmodifiableSet(builder.user.getGroups().stream().map(g -> g.getId()).collect(toSet()));
            
        } else if(builder.context != null) {
            
            for(Symbol symbol : Arrays.asList(USER_ID, PRIMARY_GROUP_ID, GROUP_IDS)) {
                if(!builder.context.isDefined(symbol)) {
                    throw new IllegalArgumentException("Missing " + symbol + " in Jena Context.");
                }
            }
            
            this.userId = builder.context.get(USER_ID);
            this.primaryGroupId = builder.context.get(PRIMARY_GROUP_ID);
            this.groupIds = Collections.unmodifiableSet(builder.context.get(GROUP_IDS));
            
        } else {
            this.userId = builder.userId;
            this.primaryGroupId = builder.primaryGroupId;
            this.groupIds = builder.groupIds != null
                    ? Collections.unmodifiableSet(builder.groupIds)
                    : Collections.emptySet();
        }
    }
    
    public static InvocationContext fromContextIfEmpty(InvocationContext invocationContext, Context context) {
        if(invocationContext.isEmpty()) {
            return new Builder().fromContext(context).build();
        }
        return invocationContext;
    }
    
    public void transferContext(org.apache.jena.sparql.util.Context jenaContext) {
        jenaContext.put(InvocationContext.USER_ID, this.getUserId());
        jenaContext.put(InvocationContext.PRIMARY_GROUP_ID, this.getPrimaryGroupId());
        jenaContext.put(InvocationContext.GROUP_IDS, this.getGroupIds());
    }
    
    public boolean isEmpty() {
        return false;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getPrimaryGroupId() {
        return primaryGroupId;
    }

    public Set<Integer> getGroupIds() {
        return groupIds;
    }

    @Override
    public String toString() {
        return "InvocationContext{"
                + "userId=" + userId
                + ", primaryGroupId=" + primaryGroupId
                + ", groupIds=" + groupIds
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }
    
    // -------- Builder --------
    public static class Builder {

        private Integer userId;
        private Integer primaryGroupId;
        private Set<Integer> groupIds;
        private Context context;
        private User user;

        public Builder() {
            // no required fields
        }

        public Builder userId(int userId) {
            this.userId = userId;
            return this;
        }

        public Builder primaryGroupId(int primaryGroupId) {
            this.primaryGroupId = primaryGroupId;
            return addGroupId(primaryGroupId);
        }

        public Builder groupIds(Set<Integer> groupIds) {
            if (groupIds != null) {
                // defensive copy
                this.groupIds = new HashSet<>(groupIds);
            }
            return this;
        }

        public Builder addGroupId(int groupId) {
            if (this.groupIds == null) {
                this.groupIds = new HashSet<>();
            }
            this.groupIds.add(groupId);
            return this;
        }
        
        public Builder fromContext(Context context) {
            this.context = context.copy();
            return this;
        }

        public Builder fromUser(User user) {
            this.user = user;
            return this;
        }
        
        public InvocationContext build() {
            return new InvocationContext(this);
        }
    }
}
