package com.example.llmn.domain.docker;

public class DockerConstants {

    private DockerConstants() {
    }

    public static final String DOCKER_RESOURCE_KEY_CPU = "CPU";
    public static final String DOCKER_RESOURCE_KEY_MEMORY = "Memory";

    public static final String COMMAND_CONTAINER_STOP = "docker stop ";
    public static final String COMMAND_CONTAINER_RESTART = "docker restart ";
    public static final String COMMAND_CONTAINER_PS = "docker ps --format \"{{.Names}}\"";
    public static final String COMMAND_CONTAINER_STATS = "docker stats --no-stream --format \"{{.Name}}:{{.CPUPerc}}:{{.MemUsage}}\"";
}
