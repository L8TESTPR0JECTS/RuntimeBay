export interface DockerEnvironment { name: string; description: string; endpoint: string; transport: 'local' | 'ssh' | 'tls' | 'unsupported'; supported: boolean; reason: string | null; current: boolean }
export interface DockerDiscovery { available: boolean; environments: DockerEnvironment[]; error: string | null }
export interface DockerEngine { name: string; version: string; operatingSystem: string; architecture: string; cpus: number; memory: number; containers: number; running: number; images: number }
export interface DockerContainer { id: string; name: string; image: string; state: string; status: string; ports: string; created: string; composeProject: string; workspaceProject: string }
export interface DockerOverview { environment: DockerEnvironment; engine: DockerEngine; containers: DockerContainer[] }
export interface DockerImage { id: string; repository: string; tag: string; size: string; created: string }
export interface DockerVolume { name: string; driver: string; scope: string }
export interface DockerNetwork { id: string; name: string; driver: string; scope: string }
export interface DockerInventory { images: DockerImage[]; volumes: DockerVolume[]; networks: DockerNetwork[] }
export interface DockerMetrics { cpu: string; memory: string; memoryPercent: string; network: string; disk: string; pids: string }
export interface DockerDetails { id: string; name: string; image: string; state: string; startedAt: string; finishedAt: string; exitCode: number; restartPolicy: string; mounts: string[]; networks: string[]; logs: string; metrics: DockerMetrics | null; metricsError: string | null }
export interface DockerOperation { id: string; context: string; endpoint: string; image: string; state: 'queued' | 'running' | 'cancelling' | 'succeeded' | 'failed' | 'cancelled'; output: string; error: string | null; createdAt: string }
