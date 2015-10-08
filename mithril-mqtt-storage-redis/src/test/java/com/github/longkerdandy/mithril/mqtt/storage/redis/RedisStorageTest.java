package com.github.longkerdandy.mithril.mqtt.storage.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.longkerdandy.mithril.mqtt.storage.redis.util.JSONs;
import com.github.longkerdandy.mithril.mqtt.util.Topics;
import com.lambdaworks.redis.RedisFuture;
import com.lambdaworks.redis.ValueScanCursor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.github.longkerdandy.mithril.mqtt.storage.redis.RedisStorage.mapToMqtt;
import static com.github.longkerdandy.mithril.mqtt.storage.redis.RedisStorage.mqttToMap;
import static com.github.longkerdandy.mithril.mqtt.util.Topics.END;

/**
 * Redis Storage Test
 */
public class RedisStorageTest {

    private static RedisStorage redis;

    @BeforeClass
    public static void init() {
        redis = new RedisStorage("localhost", 6379);
        redis.init();
    }

    @AfterClass
    public static void destroy() {
        redis.destroy();
    }

    @Test
    public void connectedTest() throws ExecutionException, InterruptedException {
        assert redis.updateConnectedNode("client1", "node1").get().equals("OK");
        assert redis.updateConnectedNode("client2", "node1").get().equals("OK");
        assert redis.updateConnectedNode("client3", "node1").get().equals("OK");
        assert redis.updateConnectedNode("client4", "node2").get().equals("OK");
        assert redis.updateConnectedNode("client5", "node2").get().equals("OK");

        assert redis.getConnectedNode("client1").get().equals("node1");
        assert redis.getConnectedNode("client2").get().equals("node1");
        assert redis.getConnectedNode("client3").get().equals("node1");
        assert redis.getConnectedNode("client4").get().equals("node2");
        assert redis.getConnectedNode("client5").get().equals("node2");

        ValueScanCursor<String> vcs1 = redis.getConnectedClients("node1", "0", 100).get();
        assert vcs1.getValues().contains("client1");
        assert vcs1.getValues().contains("client2");
        assert vcs1.getValues().contains("client3");
        ValueScanCursor<String> vcs2 = redis.getConnectedClients("node2", "0", 100).get();
        assert vcs2.getValues().contains("client4");
        assert vcs2.getValues().contains("client5");

        assert redis.removeConnectedNode("client3", "node1").get().equals("OK");
        assert redis.removeConnectedNode("client4", "node1").get().equals("OK");   // not exist

        assert redis.getConnectedNode("client3").get() == null;
        assert redis.getConnectedNode("client4").get().equals("node2");

        vcs1 = redis.getConnectedClients("node1", "0", 100).get();
        assert !vcs1.getValues().contains("client3");
        vcs2 = redis.getConnectedClients("node2", "0", 100).get();
        assert vcs2.getValues().contains("client4");
    }

    @Test
    public void packetIdTest() throws ExecutionException, InterruptedException {
        assert redis.getNextPacketId("client1").get() == 1;
        assert redis.getNextPacketId("client1").get() == 2;
        assert redis.getNextPacketId("client1").get() == 3;

        redis.conn.async().set(RedisKey.nextPacketId("client1"), "65533").get();

        assert redis.getNextPacketId("client1").get() == 65534;
        assert redis.getNextPacketId("client1").get() == 65535;
        assert redis.getNextPacketId("client1").get() == 1;
    }

    @Test
    public void existTest() throws ExecutionException, InterruptedException {
        assert redis.getSessionExist("client1").get() == null;
        redis.updateSessionExist("client1", false).get();
        assert redis.getSessionExist("client1").get().equals("0");
        redis.updateSessionExist("client1", true).get();
        assert redis.getSessionExist("client1").get().equals("1");
        redis.removeSessionExist("client1").get();
        assert redis.getSessionExist("client1").get() == null;
    }

    @Test
    public void qos2Test() throws ExecutionException, InterruptedException {
        assert redis.addQoS2MessageId("client1", 10000).get() == 1;
        assert redis.addQoS2MessageId("client1", 10001).get() == 1;
        assert redis.addQoS2MessageId("client1", 10002).get() == 1;
        assert redis.addQoS2MessageId("client1", 10000).get() == 0;

        assert redis.removeQoS2MessageId("client1", 10000).get() == 1;
        assert redis.removeQoS2MessageId("client1", 10001).get() == 1;
        assert redis.removeQoS2MessageId("client1", 10002).get() == 1;
        assert redis.removeQoS2MessageId("client1", 10001).get() == 0;
    }

    @Test
    public void inFlightTest() throws IOException, InterruptedException, ExecutionException {
        String json = "{\"menu\": {\n" +
                "  \"id\": \"file\",\n" +
                "  \"value\": \"File\",\n" +
                "  \"popup\": {\n" +
                "    \"menuItem\": [\n" +
                "      {\"value\": \"New\", \"onclick\": \"CreateNewDoc()\"},\n" +
                "      {\"value\": \"Open\", \"onclick\": \"OpenDoc()\"},\n" +
                "      {\"value\": \"Close\", \"onclick\": \"CloseDoc()\"}\n" +
                "    ]\n" +
                "  }\n" +
                "}}";
        JsonNode jn = JSONs.ObjectMapper.readTree(json);
        MqttMessage mqtt = MqttMessageFactory.newMessage(
                new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                new MqttPublishVariableHeader("menuTopic", 123456),
                Unpooled.wrappedBuffer(JSONs.ObjectMapper.writeValueAsBytes(jn))
        );

        assert redis.addInFlightMessage("client1", 123456, mqttToMap(mqtt)).get().equals("OK");
        mqtt = mapToMqtt(redis.getInFlightMessage("client1", 123456).get());

        assert mqtt.fixedHeader().messageType() == MqttMessageType.PUBLISH;
        assert !mqtt.fixedHeader().isDup();
        assert mqtt.fixedHeader().qosLevel() == MqttQoS.AT_LEAST_ONCE;
        assert !mqtt.fixedHeader().isRetain();
        assert mqtt.fixedHeader().remainingLength() == 0;
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).topicName().equals("menuTopic");
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).messageId() == 123456;
        jn = JSONs.ObjectMapper.readTree(((ByteBuf) mqtt.payload()).array());
        assert jn.get("menu").get("id").textValue().endsWith("file");
        assert jn.get("menu").get("value").textValue().endsWith("File");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("value").textValue().equals("New");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("onclick").textValue().equals("CreateNewDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("value").textValue().equals("Open");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("onclick").textValue().equals("OpenDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("value").textValue().equals("Close");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("onclick").textValue().equals("CloseDoc()");

        assert redis.getAllInFlightMessageIds("client1").get().contains("123456");

        assert redis.removeInFlightMessage("client1", 123456).get().equals("OK");

        assert redis.getInFlightMessage("client1", 123456).get().isEmpty();
        assert !redis.getAllInFlightMessageIds("client1").get().contains("123456");

        mqtt = MqttMessageFactory.newMessage(mqtt.fixedHeader(), new MqttPublishVariableHeader("menuTopic", 10000), mqtt.payload());
        assert redis.addInFlightMessage("client1", 10000, mqttToMap(mqtt)).get().equals("OK");
        mqtt = MqttMessageFactory.newMessage(mqtt.fixedHeader(), new MqttPublishVariableHeader("menuTopic", 10001), mqtt.payload());
        assert redis.addInFlightMessage("client1", 10001, mqttToMap(mqtt)).get().equals("OK");
        mqtt = MqttMessageFactory.newMessage(mqtt.fixedHeader(), new MqttPublishVariableHeader("menuTopic", 10002), mqtt.payload());
        assert redis.addInFlightMessage("client1", 10002, mqttToMap(mqtt)).get().equals("OK");

        List<String> ids = redis.getAllInFlightMessageIds("client1").get();
        assert ids.size() == 3;
        assert ids.get(0).equals("10000");
        assert ids.get(1).equals("10001");
        assert ids.get(2).equals("10002");
    }

    @Test
    public void subscriptionTest() throws ExecutionException, InterruptedException {
        assert redis.updateSubscription("client1", Topics.sanitizeTopicFilter("a/+/e"), "0").get().equals("OK");
        assert redis.updateSubscription("client1", Topics.sanitizeTopicFilter("a/+"), "1").get().equals("OK");
        assert redis.updateSubscription("client1", Topics.sanitizeTopicName("a/c/e"), "2").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicFilter("a/#"), "0").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicFilter("a/+"), "1").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicName("a/c/e"), "2").get().equals("OK");

        assert redis.getClientSubscriptions("client1").get().get("a/+/e/^").equals("0");
        assert redis.getClientSubscriptions("client1").get().get("a/+/^").equals("1");
        assert redis.getClientSubscriptions("client1").get().get("a/c/e/^").equals("2");
        assert redis.getClientSubscriptions("client2").get().get("a/#/^").equals("0");
        assert redis.getClientSubscriptions("client2").get().get("a/+/^").equals("1");
        assert redis.getClientSubscriptions("client2").get().get("a/c/e/^").equals("2");

        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+/e")).get().get("client1").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client1").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client2").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicName("a/c/e")).get().get("client1").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicName("a/c/e")).get().get("client2").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/#")).get().get("client2").equals("0");

        assert redis.removeSubscription("client1", Topics.sanitizeTopicFilter("a/+")).get().equals("OK");

        assert !redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().containsKey("client1");
        assert !redis.getClientSubscriptions("client1").get().containsKey("a/+/^");
    }

    @Test
    public void matchTopicFilterTest() throws ExecutionException, InterruptedException {
        assert redis.updateSubscription("client1", Topics.sanitizeTopicFilter("a/+/e"), "0").get().equals("OK");
        assert redis.updateSubscription("client1", Topics.sanitizeTopicFilter("a/+"), "1").get().equals("OK");
        assert redis.updateSubscription("client1", Topics.sanitizeTopicFilter("a/c/f/#"), "2").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicFilter("a/#"), "0").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicFilter("a/c/+/+"), "1").get().equals("OK");
        assert redis.updateSubscription("client2", Topics.sanitizeTopicFilter("a/d/#"), "2").get().equals("OK");

        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+/e")).get().get("client1").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/+")).get().get("client1").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/c/f/#")).get().get("client1").equals("2");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/#")).get().get("client2").equals("0");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/c/+/+")).get().get("client2").equals("1");
        assert redis.getTopicSubscriptions(Topics.sanitizeTopicFilter("a/d/#")).get().get("client2").equals("2");

        Map<String, String> result = new HashMap<>();
        getMatchFilterSubscriptions(Topics.sanitizeTopicName("a/c/f"), 0, result);
        assert result.get("client1").equals("2");
        assert result.get("client2").equals("0");

        result.clear();
        getMatchFilterSubscriptions(Topics.sanitizeTopicName("a/d/e"), 0, result);
        assert result.get("client1").equals("0");
        assert result.containsKey("client2");

        result.clear();
        getMatchFilterSubscriptions(Topics.sanitizeTopicName("a/b/c/d"), 0, result);
        assert !result.containsKey("client1");
        assert result.get("client2").equals("0");
    }

    @Test
    public void retainTest() throws InterruptedException, IOException, ExecutionException {
        String json = "{\"menu\": {\n" +
                "  \"id\": \"file\",\n" +
                "  \"value\": \"File\",\n" +
                "  \"popup\": {\n" +
                "    \"menuItem\": [\n" +
                "      {\"value\": \"New\", \"onclick\": \"CreateNewDoc()\"},\n" +
                "      {\"value\": \"Open\", \"onclick\": \"OpenDoc()\"},\n" +
                "      {\"value\": \"Close\", \"onclick\": \"CloseDoc()\"}\n" +
                "    ]\n" +
                "  }\n" +
                "}}";
        JsonNode jn = JSONs.ObjectMapper.readTree(json);
        MqttMessage mqtt = MqttMessageFactory.newMessage(
                new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_LEAST_ONCE, false, 0),
                new MqttPublishVariableHeader("menuTopic", 123456),
                Unpooled.wrappedBuffer(JSONs.ObjectMapper.writeValueAsBytes(jn))
        );

        complete(redis.addRetainMessage(Topics.sanitize("a/b/c/d"), 123456, mqttToMap(mqtt)));

        assert redis.getAllRetainMessageIds(Topics.sanitize("a/b/c/d")).get().contains("123456");
        mqtt = mapToMqtt(redis.getRetainMessage(Topics.sanitize("a/b/c/d"), 123456).get());
        assert mqtt.fixedHeader().messageType() == MqttMessageType.PUBLISH;
        assert !mqtt.fixedHeader().isDup();
        assert mqtt.fixedHeader().qosLevel() == MqttQoS.AT_LEAST_ONCE;
        assert !mqtt.fixedHeader().isRetain();
        assert mqtt.fixedHeader().remainingLength() == 0;
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).topicName().equals("menuTopic");
        assert ((MqttPublishVariableHeader) mqtt.variableHeader()).messageId() == 123456;
        jn = JSONs.ObjectMapper.readTree(((ByteBuf) mqtt.payload()).array());
        assert jn.get("menu").get("id").textValue().endsWith("file");
        assert jn.get("menu").get("value").textValue().endsWith("File");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("value").textValue().equals("New");
        assert jn.get("menu").get("popup").get("menuItem").get(0).get("onclick").textValue().equals("CreateNewDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("value").textValue().equals("Open");
        assert jn.get("menu").get("popup").get("menuItem").get(1).get("onclick").textValue().equals("OpenDoc()");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("value").textValue().equals("Close");
        assert jn.get("menu").get("popup").get("menuItem").get(2).get("onclick").textValue().equals("CloseDoc()");

        assert !redis.getAllRetainMessageIds(Topics.sanitize("a/b/c/d/e")).get().contains("123456");
        assert mapToMqtt(redis.getRetainMessage(Topics.sanitize("a/b/c/d/e"), 123456).get()) == null;
    }

    @After
    public void clear() throws ExecutionException, InterruptedException {
        redis.conn.async().flushdb().get();
    }

    /**
     * Wait asynchronous tasks completed
     */
    protected void complete(List<RedisFuture> futures) throws InterruptedException {
        for (RedisFuture future : futures) {
            future.await(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Traverse topic tree, find all matched subscribers
     */
    protected void getMatchFilterSubscriptions(List<String> topicLevels, int index, Map<String, String> result) throws ExecutionException, InterruptedException {
        List<String> children = redis.getMatchTopicFilter(topicLevels, index).get();
        // last one
        if (children.size() == 2) {
            int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
            int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
            if (c > 0) {
                result.putAll(redis.getTopicSubscriptions(topicLevels).get());
            }
            if (s > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                newTopicLevels.add("#");
                newTopicLevels.add(END);
                result.putAll(redis.getTopicSubscriptions(newTopicLevels).get());
            }
        }
        // not last one
        else if (children.size() == 3) {
            int c = children.get(0) == null ? 0 : Integer.parseInt(children.get(0)); // char
            int s = children.get(1) == null ? 0 : Integer.parseInt(children.get(1)); // #
            int p = children.get(2) == null ? 0 : Integer.parseInt(children.get(2)); // +
            if (c > 0) {
                getMatchFilterSubscriptions(topicLevels, index + 1, result);
            }
            if (s > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels.subList(0, index));
                newTopicLevels.add("#");
                newTopicLevels.add(END);
                result.putAll(redis.getTopicSubscriptions(newTopicLevels).get());
            }
            if (p > 0) {
                List<String> newTopicLevels = new ArrayList<>(topicLevels);
                newTopicLevels.set(index, "+");
                getMatchFilterSubscriptions(newTopicLevels, index + 1, result);
            }
        }
    }
}