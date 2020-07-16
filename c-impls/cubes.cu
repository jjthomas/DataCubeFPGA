#include <stdint.h>
#include <sys/time.h>
#include <fstream>
#include <stdlib.h>
#include <string.h>
#include <cuda.h>
#include <assert.h>
#include <math.h>

#include <curand.h>

using namespace std;

#define NUM_SMS 110
// must be power of two
#define BLOCK_SIZE 256
// must be power of two
#define NUM_THREADS_PER_SM 2048
#define NUM_BLOCKS_PER_SM (NUM_THREADS_PER_SM / BLOCK_SIZE)
#define NUM_BLOCKS (NUM_SMS * NUM_BLOCKS_PER_SM)
#define NUM_THREADS (NUM_THREADS_PER_SM * NUM_SMS)

#define MIN(x, y) (((x) < (y)) ? (x) : (y))

__global__ void run(uint8_t *input, uint8_t group_size, uint32_t num_input_lines, uint32_t *output) {
  uint64_t index = blockIdx.x * blockDim.x + threadIdx.x;
  uint32_t first_group_idx = MIN(index / group_size, group_size - 1);
  uint32_t second_group_idx = index % group_size;
  uint32_t *our_output = output + index * 512;
  uint8_t *input_ptr = input;

  uint32_t counts[512] = {0};

  for (uint32_t i = 0; i < num_input_lines; i++) {
    uint32_t metric = 0;
    for (uint32_t j = 0; j < 4; j++) {
      metric = metric | (input_ptr[j] << (j * 8));
    }
    input_ptr += 4;
    uint8_t counts_idx = input_ptr[first_group_idx] | (input_ptr[group_size + second_group_idx] << 4);
    counts[2 * counts_idx] += metric;
    counts[2 * counts_idx + 1]++;
    input_ptr += 2 * group_size;
  }
  for (uint32_t i = 0; i < 512; i++) {
    our_output[i] = counts[i];
  }
}

int main(int argc, char **argv) {
  assert(cudaSetDevice(0) == cudaSuccess);

  uint32_t group_size = sqrt(NUM_THREADS);
  uint32_t num_lines = 1000000;
  uint32_t input_size = (sizeof(uint32_t) + 2 * sizeof(uint8_t) * group_size) * num_lines;
  printf("group size: %d, input size: %d\n", group_size, input_size);
  uint8_t *input_dev;
  uint32_t *output_dev;
  assert(cudaMalloc((void **) &output_dev, 512 * sizeof(uint32_t) * NUM_THREADS) == cudaSuccess);
  assert(cudaMalloc((void **) &input_dev, input_size) == cudaSuccess);
  curandGenerator_t prng;
  curandCreateGenerator(&prng, CURAND_RNG_PSEUDO_XORWOW);
  curandSetPseudoRandomGeneratorSeed(prng, (unsigned long long) clock());
  curandGenerate(prng, (uint32_t *)input_dev, input_size / 4);
  // cudaMemset(input_dev, 0, input_size);

  struct timeval start, end, diff;
  gettimeofday(&start, 0);
  run<<<NUM_BLOCKS, BLOCK_SIZE>>>(input_dev, group_size, num_lines, output_dev);
  assert(cudaDeviceSynchronize() == cudaSuccess);
  gettimeofday(&end, 0);
  timersub(&end, &start, &diff);
  double secs = diff.tv_sec + diff.tv_usec / 1000000.0;

  uint32_t *output = new uint32_t[512 * NUM_THREADS];
  cudaMemcpy(output, output_dev, 512 * sizeof(uint32_t) * NUM_THREADS, cudaMemcpyDeviceToHost);
  double group_correction = pow((double)group_size / 40, 2); // group size on FPGA is only ~40
  printf("%.2f MB/s (%.2f MB/s), random byte: %d\n",
    input_size / 1000000.0 / secs,
    input_size / 1000000.0 / secs * group_correction,
    output[1]); // output[rand() % (512 * NUM_THREADS)]
  return 0;
}
