#!/usr/bin/env node

import axios from 'axios';
import { program } from 'commander';

// TypeScript will recognize process as a global with @types/node
declare const process: {
  argv: string[];
  exit(code: number): never;
};

// Define an interface for Axios error
interface AxiosError {
  response?: {
    status: number;
  };
}

interface RateLimitResponse {
  allowed: boolean;
  remaining?: number;
  retry_after?: number;
}

// Configuration request interface
interface ConfigureRequest {
  window_seconds: number;
  request_per_window: number;
}

// Configuration response interface
interface ConfigureResponse {
  window_seconds: number;
  request_per_window: number;
}

// Performance metrics interfaces
interface LatencyMetrics {
  min: number;
  max: number;
  avg: number;
  p95: number; // 95th percentile
  p99: number; // 99th percentile
}

interface ThroughputMetrics {
  requestsPerSecond: number;
  totalRequests: number;
  totalDurationMs: number;
}

interface PerformanceMetrics {
  latency: LatencyMetrics;
  throughput: ThroughputMetrics;
}

class RateLimiterTestHarness {
  private baseUrl: string;
  private ratelimitUrl: string;
  private configureUrl: string;
  private testResults: Array<[string, string]> = [];
  private performanceMetrics: PerformanceMetrics | null = null;
  private latencies: number[] = [];

  constructor(host: string, port: number) {
    this.baseUrl = `http://${host}:${port}`;
    this.ratelimitUrl = `${this.baseUrl}/api/v1/ratelimit`;
    this.configureUrl = `${this.baseUrl}/api/v1/configure`;
  }

  async runTests(): Promise<boolean> {
    console.log('Starting Rate Limiter Test Harness');
    console.log(`Target: ${this.baseUrl}`);
    console.log('-'.repeat(50));

    const tests = [
      this.testBasicRateLimiting.bind(this),
      this.testMultipleClients.bind(this),
      this.testConcurrentRequests.bind(this),
      this.testConfigurationChange.bind(this),
      this.testPerformance.bind(this),
    ];

    let allPassed = true;

    for (const test of tests) {
      const testName = test.name
        .replace('test', '')
        .replace(/([A-Z])/g, ' $1')
        .trim();

      console.log(`\nRunning Test: ${testName}`);

      try {
        const result = await test();
        if (result) {
          console.log(`✅ ${testName}: PASSED`);
          this.testResults.push([testName, 'PASSED']);
        } else {
          console.log(`❌ ${testName}: FAILED`);
          this.testResults.push([testName, 'FAILED']);
          allPassed = false;
        }
      } catch (e: unknown) {
        const errorMessage = e instanceof Error ? e.message : String(e);
        console.log(`❌ ${testName}: ERROR - ${errorMessage}`);
        this.testResults.push([testName, `ERROR: ${errorMessage}`]);
        allPassed = false;
      }

      // Reset state between tests by waiting
      await this.sleep(2000);
    }

    console.log('\n' + '-'.repeat(50));
    console.log('Test Results Summary:');
    for (const [name, result] of this.testResults) {
      console.log(`${name}: ${result}`);
    }

    // Print performance metrics if available
    if (this.performanceMetrics) {
      console.log('\n' + '-'.repeat(50));
      console.log('Performance Metrics:');

      const { latency, throughput } = this.performanceMetrics;

      console.log('\nLatency (ms):');
      console.log(`  Min: ${latency.min.toFixed(2)}`);
      console.log(`  Max: ${latency.max.toFixed(2)}`);
      console.log(`  Avg: ${latency.avg.toFixed(2)}`);
      console.log(`  P95: ${latency.p95.toFixed(2)}`);
      console.log(`  P99: ${latency.p99.toFixed(2)}`);

      console.log('\nThroughput:');
      console.log(`  Requests/second: ${throughput.requestsPerSecond.toFixed(2)}`);
      console.log(`  Total Requests: ${throughput.totalRequests}`);
      console.log(`  Total Duration: ${(throughput.totalDurationMs / 1000).toFixed(2)} seconds`);
    }

    // Print evaluation rubric
    console.log('\n' + '-'.repeat(50));
    console.log('# Rate Limiter Challenge Evaluation Rubric');

    // Calculate scores based on test results
    const functionalityScore = this.calculateFunctionalityScore();
    const performanceScore = this.calculatePerformanceScore();

    console.log('\n## Functionality (40 points)');
    console.log('\n| Criteria | Points | Notes |');
    console.log('|----------|--------|-------|');
    console.log(
      `| Basic rate limiting works correctly | ${functionalityScore.basic} / 20 | ${functionalityScore.basicNotes} |`,
    );
    console.log(
      `| Multiple clients have separate rate limits | ${functionalityScore.multipleClients} / 10 | ${functionalityScore.multipleClientsNotes} |`,
    );
    console.log(
      `| Concurrent requests are handled correctly | ${functionalityScore.concurrent} / 10 | ${functionalityScore.concurrentNotes} |`,
    );
    console.log(
      `| Configuration changes are honored | ${functionalityScore.configChange} / 10 | ${functionalityScore.configChangeNotes} |`,
    );

    console.log('\n## Performance (15 points)');
    console.log('\n| Criteria | Points | Notes |');
    console.log('|----------|--------|-------|');
    console.log(
      `| Low latency for rate limit decisions | ${performanceScore.latency} / 7 | ${performanceScore.latencyNotes} |`,
    );
    console.log(
      `| Handles high throughput | ${performanceScore.throughput} / 8 | ${performanceScore.throughputNotes} |`,
    );

    // Calculate total score for functionality and performance
    const totalFunctionalityScore =
      functionalityScore.basic +
      functionalityScore.multipleClients +
      functionalityScore.concurrent +
      (functionalityScore.configChange || 0);
    const totalPerformanceScore = performanceScore.latency + performanceScore.throughput;

    console.log(
      `\n## Total Score (Functionality + Performance): ${totalFunctionalityScore + totalPerformanceScore} / 65`,
    );

    if (allPassed) {
      console.log('\n🎉 All tests passed! The rate limiter implementation meets the requirements.');
    } else {
      console.log('\n❌ Some tests failed. Please review the test results.');
    }

    return allPassed;
  }

  private async checkRateLimit(clientId: string, resource: string): Promise<RateLimitResponse> {
    const payload = {
      client_id: clientId,
      resource: resource,
    };

    const startTime = performance.now();

    try {
      const response = await axios.post(this.ratelimitUrl, payload);
      const endTime = performance.now();

      // Record latency in milliseconds
      const latency = endTime - startTime;
      this.latencies.push(latency);

      return response.data;
    } catch (error: unknown) {
      const axiosError = error as AxiosError;
      if (axiosError.response) {
        throw new Error(`Unexpected status code: ${axiosError.response.status}`);
      }
      throw error;
    }
  }

  /**
   * Set the rate limiter configuration
   */
  private async setRateLimiterConfig(windowSeconds: number, requestsPerWindow: number): Promise<ConfigureResponse> {
    const payload: ConfigureRequest = {
      window_seconds: windowSeconds,
      request_per_window: requestsPerWindow,
    };

    try {
      const response = await axios.post(this.configureUrl, payload);
      return response.data;
    } catch (error: unknown) {
      const axiosError = error as AxiosError;
      if (axiosError.response) {
        throw new Error(`Unexpected status code: ${axiosError.response.status}`);
      }
      throw error;
    }
  }

  /**
   * Calculate latency metrics from recorded latencies
   */
  private calculateLatencyMetrics(): LatencyMetrics {
    if (this.latencies.length === 0) {
      return {
        min: 0,
        max: 0,
        avg: 0,
        p95: 0,
        p99: 0,
      };
    }

    // Sort latencies for percentile calculations
    const sortedLatencies = [...this.latencies].sort((a, b) => a - b);

    const min = sortedLatencies[0];
    const max = sortedLatencies[sortedLatencies.length - 1];
    const avg = sortedLatencies.reduce((sum, val) => sum + val, 0) / sortedLatencies.length;

    // Calculate percentiles
    const p95Index = Math.floor(sortedLatencies.length * 0.95);
    const p99Index = Math.floor(sortedLatencies.length * 0.99);

    const p95 = sortedLatencies[p95Index];
    const p99 = sortedLatencies[p99Index];

    return {
      min,
      max,
      avg,
      p95,
      p99,
    };
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async testBasicRateLimiting(): Promise<boolean> {
    /**
     * Test that basic rate limiting works.
     *
     * 1. Make 5 requests - all should be allowed
     * 2. Make 5 more requests - all should be blocked
     * 3. Wait and make another request - should be allowed
     */
    const clientId = 'test_client_basic';
    const resource = 'test_resource';

    await this.setRateLimiterConfig(10, 5);

    // First 5 requests should be allowed
    let allowedCount = 0;
    for (let i = 0; i < 5; i++) {
      const response = await this.checkRateLimit(clientId, resource);
      if (response.allowed) {
        allowedCount++;
      }
    }

    if (allowedCount !== 5) {
      console.log(`Expected 5 allowed requests, got ${allowedCount}`);
      return false;
    }

    // Next 5 requests should be blocked
    let blockedCount = 0;
    for (let i = 0; i < 5; i++) {
      const response = await this.checkRateLimit(clientId, resource);
      if (!response.allowed) {
        blockedCount++;
        // Verify retry_after is present and is a positive number
        if (
          response.retry_after === undefined ||
          typeof response.retry_after !== 'number' ||
          response.retry_after <= 0
        ) {
          console.log(`Expected retry_after to be a positive integer, got ${response.retry_after}`);
          return false;
        }
      }
    }

    if (blockedCount !== 5) {
      console.log(`Expected 5 blocked requests, got ${blockedCount}`);
      return false;
    }

    // Wait for rate limit to reset (60 seconds would be too long for a test)
    // This assumes the implementation uses a shorter window for testing
    console.log('Waiting for rate limit to reset (10 seconds)...');
    await this.sleep(10000);

    // One more request should be allowed now
    const response = await this.checkRateLimit(clientId, resource);
    if (!response.allowed) {
      console.log('Expected request to be allowed after waiting, but it was blocked');
      return false;
    }

    return true;
  }

  async testMultipleClients(): Promise<boolean> {
    /**
     * Test that different clients have separate rate limits.
     *
     * 1. Make 5 requests for client1 - all should be allowed
     * 2. Make 5 requests for client2 - all should be allowed
     * 3. Make 1 more request for client1 - should be blocked
     * 4. Make 1 more request for client2 - should be blocked
     */
    const client1 = 'test_client_1';
    const client2 = 'test_client_2';
    const resource = 'test_resource';

    // 5 requests for client1
    for (let i = 0; i < 5; i++) {
      const response = await this.checkRateLimit(client1, resource);
      if (!response.allowed) {
        console.log(`Expected request ${i + 1} for client1 to be allowed, but it was blocked`);
        return false;
      }
    }

    // 5 requests for client2
    for (let i = 0; i < 5; i++) {
      const response = await this.checkRateLimit(client2, resource);
      if (!response.allowed) {
        console.log(`Expected request ${i + 1} for client2 to be allowed, but it was blocked`);
        return false;
      }
    }

    // 6th request for client1 should be blocked
    const response1 = await this.checkRateLimit(client1, resource);
    if (response1.allowed) {
      console.log('Expected 6th request for client1 to be blocked, but it was allowed');
      return false;
    }

    // 6th request for client2 should be blocked
    const response2 = await this.checkRateLimit(client2, resource);
    if (response2.allowed) {
      console.log('Expected 6th request for client2 to be blocked, but it was allowed');
      return false;
    }

    return true;
  }

  async testConcurrentRequests(): Promise<boolean> {
    /**
     * Test that the rate limiter handles concurrent requests correctly.
     *
     * 1. Make 20 concurrent requests
     * 2. Exactly 5 should be allowed, 15 should be blocked
     */
    const clientId = 'test_client_concurrent';
    const resource = 'test_resource';
    const numRequests = 20;

    // Wait to ensure any previous rate limits have reset
    await this.sleep(10000);

    // Create an array of promises for concurrent requests
    const promises = Array(numRequests)
      .fill(0)
      .map(() => this.checkRateLimit(clientId, resource));

    // Wait for all requests to complete
    const results = await Promise.all(promises);

    const allowedCount = results.filter((r) => r.allowed).length;
    const blockedCount = results.filter((r) => !r.allowed).length;

    if (allowedCount !== 5) {
      console.log(`Expected exactly 5 allowed requests, got ${allowedCount}`);
      return false;
    }

    if (blockedCount !== 15) {
      console.log(`Expected exactly 15 blocked requests, got ${blockedCount}`);
      return false;
    }

    return true;
  }

  /**
   * Test that configuration changes are honored
   */
  async testConfigurationChange(): Promise<boolean> {
    /**
     * Test that configuration changes are properly applied and honored.
     *
     * 1. Set a new configuration with higher limits (10 requests per window)
     * 2. Make 10 requests - all should be allowed
     * 3. Make 1 more request - should be blocked
     * 4. Reset configuration to original values for other tests
     */
    const clientId = 'test_client_config';
    const resource = 'test_resource_config';

    console.log('Setting new configuration: 10 requests per 10 second window');

    // Set new configuration: 10 requests per 10 second window
    await this.setRateLimiterConfig(10, 10);

    // Make 10 requests - all should be allowed with new configuration
    let allowedCount = 0;
    for (let i = 0; i < 10; i++) {
      const response = await this.checkRateLimit(clientId, resource);
      if (response.allowed) {
        allowedCount++;
      }
    }

    if (allowedCount !== 10) {
      console.log(`Expected 10 allowed requests with new configuration, got ${allowedCount}`);
      return false;
    }

    // 11th request should be blocked
    const response = await this.checkRateLimit(clientId, resource);
    if (response.allowed) {
      console.log('Expected 11th request to be blocked with new configuration, but it was allowed');
      return false;
    }

    // Reset configuration to original values (5 requests per 10 second window)
    console.log('Resetting configuration to original values');
    await this.setRateLimiterConfig(10, 5);

    return true;
  }

  /**
   * Test performance characteristics of the rate limiter
   * - Latency: How quickly does the rate limiter respond?
   * - Throughput: How many requests can the rate limiter handle per second?
   */
  async testPerformance(): Promise<boolean> {
    console.log('Running performance tests...');

    const clientId = 'test_client_performance';
    const resource = 'test_resource_performance';
    const numRequests = 100000; // Number of requests to send for performance testing

    await this.setRateLimiterConfig(99, 1);

    // Reset latencies array
    this.latencies = [];

    // Measure throughput
    const startTime = performance.now();

    const batchSize = 99;
    const batches = Math.ceil(numRequests / batchSize);

    for (let i = 0; i < batches; i++) {
      const batchPromises = [];
      const batchStart = i * batchSize;
      const batchEnd = Math.min((i + 1) * batchSize, numRequests);

      for (let j = batchStart; j < batchEnd; j++) {
        batchPromises.push(this.checkRateLimit(clientId, `${resource}_${j}`));
      }

      await Promise.all(batchPromises);
    }

    const endTime = performance.now();
    const totalDuration = endTime - startTime;

    // Calculate metrics
    const latencyMetrics = this.calculateLatencyMetrics();

    const throughputMetrics: ThroughputMetrics = {
      requestsPerSecond: (numRequests / totalDuration) * 1000,
      totalRequests: numRequests,
      totalDurationMs: totalDuration,
    };

    // Store performance metrics
    this.performanceMetrics = {
      latency: latencyMetrics,
      throughput: throughputMetrics,
    };

    await this.setRateLimiterConfig(10, 5);
    return true;
  }

  /**
   * Calculate functionality score based on test results
   */
  private calculateFunctionalityScore(): {
    basic: number;
    basicNotes: string;
    multipleClients: number;
    multipleClientsNotes: string;
    concurrent: number;
    concurrentNotes: string;
    configChange?: number;
    configChangeNotes?: string;
  } {
    const result = {
      basic: 0,
      basicNotes: '',
      multipleClients: 0,
      multipleClientsNotes: '',
      concurrent: 0,
      concurrentNotes: '',
      configChange: 0,
      configChangeNotes: '',
    };

    // Check if basic rate limiting test passed
    const basicTestResult = this.testResults.find(([name]) => name.includes('Basic Rate Limiting'));
    if (basicTestResult) {
      if (basicTestResult[1] === 'PASSED') {
        result.basic = 20;
        result.basicNotes = 'Test passed successfully';
      } else {
        result.basic = 0;
        result.basicNotes = 'Test failed';
      }
    }

    // Check if multiple clients test passed
    const multipleClientsTestResult = this.testResults.find(([name]) => name.includes('Multiple Clients'));
    if (multipleClientsTestResult) {
      if (multipleClientsTestResult[1] === 'PASSED') {
        result.multipleClients = 10;
        result.multipleClientsNotes = 'Test passed successfully';
      } else {
        result.multipleClients = 0;
        result.multipleClientsNotes = 'Test failed';
      }
    }

    // Check if concurrent requests test passed
    const concurrentTestResult = this.testResults.find(([name]) => name.includes('Concurrent Requests'));
    if (concurrentTestResult) {
      if (concurrentTestResult[1] === 'PASSED') {
        result.concurrent = 10;
        result.concurrentNotes = 'Test passed successfully';
      } else {
        result.concurrent = 0;
        result.concurrentNotes = 'Test failed';
      }
    }

    // Check if configuration change test passed
    const configChangeTestResult = this.testResults.find(([name]) => name.includes('Configuration Change'));
    if (configChangeTestResult) {
      if (configChangeTestResult[1] === 'PASSED') {
        result.configChange = 10;
        result.configChangeNotes = 'Test passed successfully';
      } else {
        result.configChange = 0;
        result.configChangeNotes = 'Test failed';
      }
    }

    return result;
  }

  /**
   * Calculate performance score based on performance metrics
   */
  private calculatePerformanceScore(): {
    latency: number;
    latencyNotes: string;
    throughput: number;
    throughputNotes: string;
  } {
    const result = {
      latency: 0,
      latencyNotes: '',
      throughput: 0,
      throughputNotes: '',
    };

    if (!this.performanceMetrics) {
      return result;
    }

    const { latency, throughput } = this.performanceMetrics;

    // Score latency (lower is better)
    if (latency.avg < 1) {
      result.latency = 7;
      result.latencyNotes = 'Excellent latency (<1ms)';
    } else if (latency.avg < 2) {
      result.latency = 6;
      result.latencyNotes = 'Very good latency (<2ms)';
    } else if (latency.avg < 5) {
      result.latency = 5;
      result.latencyNotes = 'Good latency (<5ms)';
    } else if (latency.avg < 10) {
      result.latency = 3;
      result.latencyNotes = 'Acceptable latency (<10ms)';
    } else if (latency.avg < 100) {
      result.latency = 1;
      result.latencyNotes = 'Poor latency (<100ms)';
    } else {
      result.latency = 0;
      result.latencyNotes = 'Unacceptable latency (>100ms)';
    }

    // Score throughput (higher is better)
    if (throughput.requestsPerSecond > 15000) {
      result.throughput = 8;
      result.throughputNotes = 'Excellent throughput (>15000 req/s)';
    } else if (throughput.requestsPerSecond > 10000) {
      result.throughput = 6;
      result.throughputNotes = 'Very good throughput (>10000 req/s)';
    } else if (throughput.requestsPerSecond > 1000) {
      result.throughput = 4;
      result.throughputNotes = 'Good throughput (>1000 req/s)';
    } else if (throughput.requestsPerSecond > 200) {
      result.throughput = 2;
      result.throughputNotes = 'Acceptable throughput (>200 req/s)';
    } else if (throughput.requestsPerSecond > 100) {
      result.throughput = 1;
      result.throughputNotes = 'Poor throughput (>100 req/s)';
    } else {
      result.throughput = 0;
      result.throughputNotes = 'Unacceptable throughput (<100 req/s)';
    }

    return result;
  }
}

// Parse command line arguments
program
  .option('--host <host>', 'Host where the rate limiter service is running', 'localhost')
  .option('--port <port>', 'Port where the rate limiter service is running', '8080')
  .parse(process.argv);

const options = program.opts();

// Run the tests
const testHarness = new RateLimiterTestHarness(options.host, parseInt(options.port, 10));
testHarness
  .runTests()
  .then((success) => {
    process.exit(success ? 0 : 1);
  })
  .catch((error: unknown) => {
    console.error('Error running tests:', error instanceof Error ? error.message : String(error));
    process.exit(1);
  });
