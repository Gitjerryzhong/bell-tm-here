package cn.edu.bnuz.bell.here

import cn.edu.bnuz.bell.http.BadRequestException
import cn.edu.bnuz.bell.http.ForbiddenException
import cn.edu.bnuz.bell.http.NotFoundException
import cn.edu.bnuz.bell.master.Term
import cn.edu.bnuz.bell.operation.ScheduleService
import cn.edu.bnuz.bell.operation.TaskSchedule
import cn.edu.bnuz.bell.organization.Student
import cn.edu.bnuz.bell.security.User
import cn.edu.bnuz.bell.master.TermService
import cn.edu.bnuz.bell.workflow.DomainStateMachineHandler
import cn.edu.bnuz.bell.workflow.WorkflowActivity
import cn.edu.bnuz.bell.workflow.WorkflowInstance
import cn.edu.bnuz.bell.workflow.Workitem
import cn.edu.bnuz.bell.workflow.commands.SubmitCommand
import grails.gorm.transactions.Transactional

import javax.annotation.Resource

@Transactional
class StudentLeaveFormService {
    TermService termService
    ScheduleService scheduleService

    @Resource(name='studentLeaveFormStateHandler')
    DomainStateMachineHandler domainStateMachineHandler

    def list(String studentId, Integer offset, Integer max) {
        StudentLeaveForm.executeQuery '''
select new map(
  form.id as id,
  form.type as type,
  form.reason as reason,
  form.dateCreated as dateCreated,
  form.status as status
)
from StudentLeaveForm form
where form.student.id = :studentId
order by form.dateCreated desc
''', [studentId: studentId], [offset: offset, max: max]
    }

    def listCount(String studentId) {
        StudentLeaveForm.countByStudent(Student.load(studentId))
    }

    Map getFormInfo(Long id) {
        def results = StudentLeaveForm.executeQuery '''
select new map(
  form.id as id,
  form.term.id as term,
  student.id as studentId,
  student.name as studentName,
  adminClass.name as adminClass,
  form.type as type,
  form.reason as reason,
  form.dateCreated as dateCreated,
  form.dateModified as dateModified,
  approver.name as approver,
  form.dateApproved as dateApproved,
  form.status as status,
  form.workflowInstance.id as workflowInstanceId
)
from StudentLeaveForm form
join form.student student
join student.adminClass adminClass
left join form.approver approver
where form.id = :id
''', [id: id]
        if (!results) {
            return null
        }

        def form = results[0]

        form.items = StudentLeaveItem.executeQuery '''
select new map(
  item.id as id,
  item.week as week,
  item.dayOfWeek as dayOfWeek,
  item.taskSchedule.id as taskScheduleId
)
from StudentLeaveItem item
where item.form.id = :formId
''', [formId: id]

        return form
    }

    def getFormForShow(String studentId, Long id) {
        def form = getFormInfo(id)

        if (!form) {
            throw new NotFoundException()
        }

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        form.editable = domainStateMachineHandler.canUpdate(form)

        def schedules = scheduleService.getStudentSchedules(form.term, studentId)

        return [
                schedules: schedules,
                form: form,
        ]
    }

    def getFormForCreate(String studentId) {
        def term = termService.activeTerm
        def schedules = scheduleService.getStudentSchedules(term.id, studentId)

        return [
                term: [
                        startWeek: term.startWeek,
                        endWeek: term.endWeek,
                        currentWeek: term.currentWeek,
                ],
                form: [
                        items: [],
                ],
                schedules: schedules,
                freeListens: [],
                existedItems: findExistedLeaveItems(term, studentId, 0),
        ]
    }

    def getFormForEdit(String studentId, Long id) {
        def form = getFormInfo(id)

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        if (!domainStateMachineHandler.canUpdate(form)) {
            throw new BadRequestException()
        }

        def schedules = scheduleService.getStudentSchedules(form.term, studentId)
        def term = Term.get(form.term)

        return [
                term: [
                        startWeek: term.startWeek,
                        endWeek: term.endWeek,
                        currentWeek: term.currentWeek,
                ],
                form: form,
                schedules: schedules,
                freeListens: [],
                existedItems: findExistedLeaveItems(term, studentId, id),
        ]
    }

    /**
     * 获取指定学生和学期的所有请假项，用于前台判断请假是否冲突。
     * @param studentId 学生ID
     * @param term 学期
     * @param excludeFormId 不包含的此请假条ID，用于编辑表单；新建请假条时，可省略此参数
     * @return 请假项列表
     */
    List findExistedLeaveItems(Term term, String studentId, Long excludeFormId) {
        StudentLeaveItem.executeQuery '''
select new map (
  item.id as id,
  item.week as week,
  item.dayOfWeek as dayOfWeek,
  item.taskSchedule.id as taskScheduleId
) 
from StudentLeaveItem item
join item.form form
where form.student.id = :studentId
  and form.term = :term
  and form.id != :excludeFormId
''', [studentId: studentId, term: term, excludeFormId: excludeFormId]
    }

    StudentLeaveForm create(String studentId, StudentLeaveFormCommand cmd) {
        def now = new Date()

        StudentLeaveForm form = new StudentLeaveForm(
                student: Student.load(studentId),
                term: termService.activeTerm,
                type: cmd.type,
                reason: cmd.reason,
                dateCreated: now,
                dateModified: now,
                status: domainStateMachineHandler.initialState
        )

        cmd.addedItems.each { item->
            form.addToItems(new StudentLeaveItem(
                    week: item.week,
                    dayOfWeek: item.dayOfWeek,
                    taskSchedule: item.taskScheduleId ? TaskSchedule.load(item.taskScheduleId) : null
            ))
        }

        form.save()

        domainStateMachineHandler.create(form, studentId)

        return form
    }

    StudentLeaveForm update(String studentId, StudentLeaveFormCommand cmd) {
        StudentLeaveForm form = StudentLeaveForm.get(cmd.id)

        if (!form) {
            throw new NotFoundException()
        }

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        if (!domainStateMachineHandler.canUpdate(form)) {
            throw new BadRequestException()
        }

        form.type = cmd.type
        form.reason = cmd.reason
        form.dateModified = new Date()

        cmd.addedItems.each { item ->
            form.addToItems(new StudentLeaveItem(
                    week: item.week,
                    dayOfWeek: item.dayOfWeek,
                    taskSchedule: item.taskScheduleId ? TaskSchedule.load(item.taskScheduleId) : null
            ))
        }

        cmd.removedItems.each {
            def leaveItem = StudentLeaveItem.load(it)
            form.removeFromItems(leaveItem)
            leaveItem.delete()
        }

        domainStateMachineHandler.update(form, studentId)

        form.save()
    }

    void delete(String studentId, Long id) {
        StudentLeaveForm form = StudentLeaveForm.get(id)

        if (!form) {
            throw new NotFoundException()
        }

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        if (!domainStateMachineHandler.canUpdate(form)) {
            throw new BadRequestException()
        }

        if (form.workflowInstance) {
            form.workflowInstance.delete()
        }

        form.delete()
    }

    def submit(String studentId, SubmitCommand cmd) {
        StudentLeaveForm form = StudentLeaveForm.get(cmd.id)

        if (!form) {
            throw new NotFoundException()
        }

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        if (!domainStateMachineHandler.canSubmit(form)) {
            throw new BadRequestException()
        }

        domainStateMachineHandler.submit(form, studentId, cmd.to, cmd.comment, cmd.title)

        form.dateSubmitted = new Date()
        form.save()
    }

    def finish(String studentId, Long id) {
        StudentLeaveForm form = StudentLeaveForm.get(id)

        if (!form) {
            throw new NotFoundException()
        }

        if (form.studentId != studentId) {
            throw new ForbiddenException()
        }

        if (!domainStateMachineHandler.canFinish(form)) {
            throw new BadRequestException()
        }

        def workitem = Workitem.findByInstanceAndActivityAndToAndDateProcessedIsNull(
                WorkflowInstance.load(form.workflowInstanceId),
                WorkflowActivity.load('student.leave.finish'),
                User.load(studentId),
        )

        domainStateMachineHandler.finish(form, studentId, workitem.id)

        form.save()
    }
}
