package org.registrynode.model;

import java.util.Map;

public class Currency {

	private String namespace;
	private Map<String, Long> values;

	public Currency() {
	}

	public Currency(String namespace, Map<String, Long> values) {
		this.namespace = namespace;
		this.values = values;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public Map<String, Long> getValues() {
		return values;
	}

	public void setValues(Map<String, Long> values) {
		this.values = values;
	}
}
