/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AERON_AERON_PUBLICATION_IMAGE_H
#define AERON_AERON_PUBLICATION_IMAGE_H

#include "aeron_driver_common.h"
#include "media/aeron_receive_channel_endpoint.h"
#include "aeron_congestion_control.h"

typedef enum aeron_publication_image_status_enum
{
    AERON_PUBLICATION_IMAGE_STATUS_INIT,
    AERON_PUBLICATION_IMAGE_STATUS_INACTIVE,
    AERON_PUBLICATION_IMAGE_STATUS_ACTIVE,
    AERON_PUBLICATION_IMAGE_STATUS_LINGER,
}
aeron_publication_image_status_t;

typedef struct aeron_publication_image_stct
{
    struct aeron_publication_image_conductor_fields_stct
    {
        aeron_driver_managed_resource_t managed_resource;
        aeron_subscribeable_t subscribeable;
        int64_t clean_position;
        int64_t time_of_last_activity_ns;
        int64_t liveness_timeout_ns;
        bool has_reached_end_of_life;
        aeron_publication_image_status_t status;
    }
    conductor_fields;

    uint8_t conductor_fields_pad[
        (2 * AERON_CACHE_LINE_LENGTH) - sizeof(struct aeron_publication_image_conductor_fields_stct)];

    struct sockaddr_storage control_address;
    struct sockaddr_storage source_address;

    aeron_mapped_raw_log_t mapped_raw_log;
    aeron_position_t rcv_hwm_position;
    aeron_position_t rcv_pos_position;
    aeron_logbuffer_metadata_t *log_meta_data;

    aeron_receive_channel_endpoint_t *endpoint;
    aeron_congestion_control_strategy_t *congestion_control;
    aeron_clock_func_t nano_clock;
    aeron_clock_func_t epoch_clock;

    char *log_file_name;
    int64_t correlation_id;
    int32_t session_id;
    int32_t stream_id;
    int32_t initial_term_id;
    int32_t active_term_id;
    int32_t initial_term_offset;
    int32_t term_length;
    int32_t mtu_length;
    int32_t term_length_mask;
    size_t log_file_name_length;
    size_t position_bits_to_shift;
    aeron_map_raw_log_close_func_t map_raw_log_close_func;

    int64_t last_packet_timestamp_ns;

    int64_t next_sm_position;
    int32_t next_sm_receiver_window_length;

    int64_t *heartbeats_received_counter;
    int64_t *flow_control_under_runs_counter;
    int64_t *flow_control_over_runs_counter;
}
aeron_publication_image_t;

int aeron_publication_image_create(
    aeron_publication_image_t **image,
    aeron_receive_channel_endpoint_t *endpoint,
    aeron_driver_context_t *context,
    int64_t correlation_id,
    int32_t session_id,
    int32_t stream_id,
    int32_t initial_term_id,
    int32_t active_term_id,
    int32_t initial_term_offset,
    aeron_position_t *rcv_hwm_position,
    aeron_position_t *rcv_pos_position,
    aeron_congestion_control_strategy_t *congestion_control,
    struct sockaddr_storage *control_address,
    struct sockaddr_storage *source_address,
    int32_t term_buffer_length,
    int32_t sender_mtu_length,
    bool is_reliable,
    aeron_system_counters_t *system_counters);

int aeron_publication_image_close(aeron_counters_manager_t *counters_manager, aeron_publication_image_t *image);

void aeron_publication_image_track_rebuild(
    aeron_publication_image_t *image, int64_t now_nw, int64_t status_message_timeout);

int aeron_publication_image_insert_packet(
    aeron_publication_image_t *image, int32_t term_id, int32_t term_offset, const uint8_t *buffer, size_t length);

int aeron_publication_image_on_rttm(
    aeron_publication_image_t *image, aeron_rttm_header_t *header, struct sockaddr_storage *addr);

inline bool aeron_publication_image_is_heartbeat(const uint8_t *buffer, size_t length)
{
    return (length == AERON_DATA_HEADER_LENGTH && 0 == ((aeron_frame_header_t *)buffer)->frame_length);
}

inline bool aeron_publication_image_is_end_of_stream(const uint8_t *buffer, size_t length)
{
    return ((aeron_frame_header_t *)buffer)->flags &
        (AERON_DATA_HEADER_EOS_FLAG | AERON_DATA_HEADER_BEGIN_FLAG | AERON_DATA_HEADER_END_FLAG);
}

inline bool aeron_publication_image_is_flow_control_under_run(
    aeron_publication_image_t *image, int64_t window_position, int64_t packet_position)
{
    const bool is_flow_control_under_run = packet_position < window_position;

    if (is_flow_control_under_run)
    {
        aeron_counter_ordered_increment(image->flow_control_under_runs_counter, 1);
    }

    return is_flow_control_under_run;
}

inline bool aeron_publication_image_is_flow_control_over_run(
    aeron_publication_image_t *image, int64_t window_position, int64_t proposed_position)
{
    const bool is_flow_control_over_run = proposed_position > (window_position + image->next_sm_receiver_window_length);

    if (is_flow_control_over_run)
    {
        aeron_counter_ordered_increment(image->flow_control_over_runs_counter, 1);
    }

    return is_flow_control_over_run;
}

inline void aeron_publication_image_hwm_candidate(aeron_publication_image_t *image, int64_t proposed_position)
{
    image->last_packet_timestamp_ns = image->nano_clock();
    aeron_counter_propose_max_ordered(image->rcv_hwm_position.value_addr, proposed_position);
}

#endif //AERON_AERON_PUBLICATION_IMAGE_H
