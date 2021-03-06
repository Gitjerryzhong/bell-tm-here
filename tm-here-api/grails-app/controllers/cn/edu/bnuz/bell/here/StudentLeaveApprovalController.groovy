package cn.edu.bnuz.bell.here

import cn.edu.bnuz.bell.http.BadRequestException
import cn.edu.bnuz.bell.http.ServiceExceptionHandler
import cn.edu.bnuz.bell.workflow.Event
import cn.edu.bnuz.bell.workflow.ListCommand
import cn.edu.bnuz.bell.workflow.ListType
import cn.edu.bnuz.bell.workflow.commands.AcceptCommand
import cn.edu.bnuz.bell.workflow.commands.RejectCommand
import org.springframework.security.access.prepost.PreAuthorize

@PreAuthorize('hasAuthority("PERM_STUDENT_LEAVE_APPROVE")')
class StudentLeaveApprovalController implements ServiceExceptionHandler {
    StudentLeaveApprovalService studentLeaveApprovalService

    def index(String approverId, ListCommand cmd) {
        renderJson studentLeaveApprovalService.list(approverId, cmd)
    }

    def show(String approverId, Long studentLeaveApprovalId, String id, String type) {
        ListType listType = Enum.valueOf(ListType, type)
        if (id == 'undefined') {
            renderJson studentLeaveApprovalService.getFormForReview(approverId, studentLeaveApprovalId, listType)
        } else {
            renderJson studentLeaveApprovalService.getFormForReview(approverId, studentLeaveApprovalId, listType, UUID.fromString(id))
        }
    }

    def patch(String approverId, Long studentLeaveApprovalId, String id, String op) {
        def operation = Event.valueOf(op)
        switch (operation) {
            case Event.ACCEPT:
                def cmd = new AcceptCommand()
                bindData(cmd, request.JSON)
                cmd.id = studentLeaveApprovalId
                studentLeaveApprovalService.accept(approverId, cmd, UUID.fromString(id))
                break
            case Event.REJECT:
                def cmd = new RejectCommand()
                bindData(cmd, request.JSON)
                cmd.id = studentLeaveApprovalId
                studentLeaveApprovalService.reject(approverId, cmd, UUID.fromString(id))
                break
            default:
                throw new BadRequestException()
        }

        show(approverId, studentLeaveApprovalId, id, 'todo')
    }
}
