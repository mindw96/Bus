package kr.ac.unist.bus.common.api

import kr.ac.unist.bus.arrival.application.ArrivalUnavailableException
import kr.ac.unist.bus.stop.UnsupportedStopException
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionConflictException
import kr.ac.unist.bus.tracking.application.TrackingSubscriptionValidationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(TrackingSubscriptionValidationException::class)
    fun invalidTracking(
        exception: TrackingSubscriptionValidationException,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            exception.message ?: "추적 요청이 올바르지 않습니다.",
        )
        problem.title = "추적 요청을 처리할 수 없습니다"
        problem.type = URI.create("https://unist-bus.local/problems/invalid-tracking")
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem)
    }

    @ExceptionHandler(TrackingSubscriptionConflictException::class)
    fun trackingConflict(
        exception: TrackingSubscriptionConflictException,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            exception.message ?: "추적 세션이 충돌했습니다.",
        )
        problem.title = "추적 세션 충돌"
        problem.type = URI.create("https://unist-bus.local/problems/tracking-conflict")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }

    @ExceptionHandler(UnsupportedStopException::class)
    fun unsupportedStop(exception: UnsupportedStopException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            "지원하지 않는 정류장 ID입니다: ${exception.stopId}",
        )
        problem.title = "정류장을 찾을 수 없습니다"
        problem.type = URI.create("https://unist-bus.local/problems/unsupported-stop")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem)
    }

    @ExceptionHandler(ArrivalUnavailableException::class)
    fun arrivalsUnavailable(exception: ArrivalUnavailableException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "정류장 ${exception.stopId}의 최신 정보와 사용 가능한 캐시가 없습니다.",
        )
        problem.title = "도착 정보를 사용할 수 없습니다"
        problem.type = URI.create("https://unist-bus.local/problems/arrivals-unavailable")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "30")
            .body(problem)
    }
}
