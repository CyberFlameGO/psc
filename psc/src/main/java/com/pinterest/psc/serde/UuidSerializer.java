package com.pinterest.psc.serde;

import com.pinterest.psc.config.PscConfiguration;
import com.pinterest.psc.exception.producer.SerializerException;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

public class UuidSerializer implements Serializer<UUID> {
    private String encoding = "UTF8";

    @Override
    public void configure(PscConfiguration pscConfiguration, boolean isKey) {
        String configName = isKey ? "serializer.key.encoding" : "serializer.value.encoding";
        String encodingValue = pscConfiguration.getString(configName);
        if (encodingValue == null)
            encodingValue = pscConfiguration.getString("serializer.encoding");
        if (encodingValue != null)
            encoding = encodingValue;
    }

    @Override
    public byte[] serialize(UUID data) throws SerializerException {
        try {
            if (data == null)
                return null;
            else
                return data.toString().getBytes(encoding);
        } catch (UnsupportedEncodingException e) {
            throw new SerializerException("Error when serializing UUID to byte[] due to unsupported encoding " + encoding);
        }
    }
}
