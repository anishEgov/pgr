package org.egov.localization.persistence.dto;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.egov.localization.domain.model.Message;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Setter
@Getter
public class MessageCacheEntry implements Serializable {
	private List<MessageDTO> messages;

	public MessageCacheEntry(List<Message> domainMessages) {
		this.messages = domainMessages.stream().map(MessageDTO::new).collect(Collectors.toList());
	}

	@JsonIgnore
	public List<Message> getDomainMessages() {
		return messages.stream().map(MessageDTO::toDomainMessage).collect(Collectors.toList());
	}
}
