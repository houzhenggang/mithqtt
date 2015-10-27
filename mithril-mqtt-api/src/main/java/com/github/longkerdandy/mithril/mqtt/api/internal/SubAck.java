package com.github.longkerdandy.mithril.mqtt.api.internal;

import io.netty.handler.codec.mqtt.MqttGrantedQoS;

import java.util.List;

/**
 * Represent MQTT SUBACK Message's VariableHeader and Payload
 */
@SuppressWarnings("unused")
public class SubAck {

    private int packetId;
    private List<MqttGrantedQoS> grantedQoSLevels;

    protected SubAck() {
    }

    public SubAck(int packetId, List<MqttGrantedQoS> grantedQoSLevels) {
        this.packetId = packetId;
        this.grantedQoSLevels = grantedQoSLevels;
    }

    public int getPacketId() {
        return packetId;
    }

    public void setPacketId(int packetId) {
        this.packetId = packetId;
    }

    public List<MqttGrantedQoS> getGrantedQoSLevels() {
        return grantedQoSLevels;
    }

    public void setGrantedQoSLevels(List<MqttGrantedQoS> grantedQoSLevels) {
        this.grantedQoSLevels = grantedQoSLevels;
    }
}
