package forge.externalai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Response from the external AI server containing the decision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiDecisionResponse {

    @JsonProperty("decisionId")
    private String decisionId;

    @JsonProperty("decisionType")
    private String decisionType;

    @JsonProperty("value")
    private Map<String, Object> value;

    @JsonProperty("reasoning")
    private String reasoning;

    @JsonProperty("selectedOptionId")
    private String selectedOptionId;

    @JsonProperty("selectedOptionIds")
    private List<String> selectedOptionIds;

    public AiDecisionResponse() {
    }

    public AiDecisionResponse(String decisionId, String decisionType, Map<String, Object> value,
                               String reasoning, String selectedOptionId) {
        this.decisionId = decisionId;
        this.decisionType = decisionType;
        this.value = value;
        this.reasoning = reasoning;
        this.selectedOptionId = selectedOptionId;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(String decisionId) {
        this.decisionId = decisionId;
    }

    public String getDecisionType() {
        return decisionType;
    }

    public void setDecisionType(String decisionType) {
        this.decisionType = decisionType;
    }

    public Map<String, Object> getValue() {
        return value;
    }

    public void setValue(Map<String, Object> value) {
        this.value = value;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getSelectedOptionId() {
        return selectedOptionId;
    }

    public void setSelectedOptionId(String selectedOptionId) {
        this.selectedOptionId = selectedOptionId;
    }

    public List<String> getSelectedOptionIds() {
        return selectedOptionIds;
    }

    public void setSelectedOptionIds(List<String> selectedOptionIds) {
        this.selectedOptionIds = selectedOptionIds;
    }
}
