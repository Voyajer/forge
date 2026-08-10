package forge.externalai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Request sent to the external AI server for a decision.
 * Contains the full board state and the specific decision context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiDecisionRequest {

    @JsonProperty("gameId")
    private String gameId;

    @JsonProperty("boardState")
    private Map<String, Object> boardState;

    @JsonProperty("decisionRequest")
    private DecisionContext decisionContext;

    public AiDecisionRequest() {
    }

    public AiDecisionRequest(String gameId, Map<String, Object> boardState, DecisionContext decisionContext) {
        this.gameId = gameId;
        this.boardState = boardState;
        this.decisionContext = decisionContext;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public Map<String, Object> getBoardState() {
        return boardState;
    }

    public void setBoardState(Map<String, Object> boardState) {
        this.boardState = boardState;
    }

    public DecisionContext getDecisionContext() {
        return decisionContext;
    }

    public void setDecisionContext(DecisionContext decisionContext) {
        this.decisionContext = decisionContext;
    }

    /**
     * Context for a single decision point.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DecisionContext {

        @JsonProperty("type")
        private String type;

        @JsonProperty("description")
        private String description;

        @JsonProperty("options")
        private List<Option> options;

        @JsonProperty("constraints")
        private Constraints constraints;

        @JsonProperty("prompt")
        private String prompt;

        public DecisionContext() {
        }

        public DecisionContext(String type, String description, List<Option> options,
                               Constraints constraints, String prompt) {
            this.type = type;
            this.description = description;
            this.options = options;
            this.constraints = constraints;
            this.prompt = prompt;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<Option> getOptions() {
            return options;
        }

        public void setOptions(List<Option> options) {
            this.options = options;
        }

        public Constraints getConstraints() {
            return constraints;
        }

        public void setConstraints(Constraints constraints) {
            this.constraints = constraints;
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    /**
     * A single option available for the current decision.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Option {

        @JsonProperty("id")
        private String id;

        @JsonProperty("type")
        private String type;

        @JsonProperty("label")
        private String label;

        @JsonProperty("details")
        private Map<String, Object> details;

        public Option() {
        }

        public Option(String id, String type, String label, Map<String, Object> details) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.details = details;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }
    }

    /**
     * Constraints on how many options can be selected.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Constraints {

        @JsonProperty("minChoices")
        private int minChoices;

        @JsonProperty("maxChoices")
        private int maxChoices;

        @JsonProperty("isOptional")
        private boolean isOptional;

        @JsonProperty("allowNone")
        private boolean allowNone;

        public Constraints() {
        }

        public Constraints(int minChoices, int maxChoices, boolean isOptional, boolean allowNone) {
            this.minChoices = minChoices;
            this.maxChoices = maxChoices;
            this.isOptional = isOptional;
            this.allowNone = allowNone;
        }

        public int getMinChoices() {
            return minChoices;
        }

        public void setMinChoices(int minChoices) {
            this.minChoices = minChoices;
        }

        public int getMaxChoices() {
            return maxChoices;
        }

        public void setMaxChoices(int maxChoices) {
            this.maxChoices = maxChoices;
        }

        public boolean isOptional() {
            return isOptional;
        }

        public void setOptional(boolean optional) {
            isOptional = optional;
        }

        public boolean isAllowNone() {
            return allowNone;
        }

        public void setAllowNone(boolean allowNone) {
            this.allowNone = allowNone;
        }
    }
}
